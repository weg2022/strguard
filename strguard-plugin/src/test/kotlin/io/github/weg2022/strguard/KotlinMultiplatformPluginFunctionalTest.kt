package io.github.weg2022.strguard

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.test.*

class KotlinMultiplatformPluginFunctionalTest {
    @TempDir
    lateinit var projectDirectory: Path

    @Test
    fun `protects Kotlin Multiplatform JVM target and leaves JS target unchanged`() {
        val nativeTarget = hostNativeTarget()
        writeFile(
            "settings.gradle.kts",
            """
            pluginManagement {
                includeBuild("${projectRootPath()}")
                repositories {
                    gradlePluginPortal()
                    mavenCentral()
                }
            }
            rootProject.name = "kmp-consumer"
            """.trimIndent(),
        )
        writeFile(
            "build.gradle.kts",
            """
            plugins {
                kotlin("multiplatform") version "2.1.21"
                id("io.github.weg2022.strguard")
            }

            repositories {
                mavenCentral()
            }

            kotlin {
                jvm()
                js(IR) {
                    nodejs()
                }
            }

            strGuard {
                releaseSeedHex.set("$KMP_TEST_SEED")
                targetTriple.set("${nativeTarget.rustTriple}")
                stringGuardPackages.set(listOf("sample"))
                consoleOutput.set(true)
            }
            """.trimIndent(),
        )
        writeFile(
            "src/jsMain/kotlin/sample/JsMessage.kt",
            """
            package sample

            fun jsReveal(): String = "kmp-js-unchanged-sensitive-value"
            """.trimIndent(),
        )
        writeFile(
            "src/jvmMain/kotlin/sample/Main.kt",
            """
            package sample

            fun reveal(value: String): String = "kmp-prefix-${'$'}value-sensitive-suffix"

            fun main() {
                println(reveal("runtime"))
            }
            """.trimIndent(),
        )

        val result = runner("jvmJar", "jvmRun", "jsMainClasses", "-DmainClass=sample.MainKt").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":transformStrGuardJvmMain")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":jsMainClasses")?.outcome)
        assertEquals(null, result.task(":transformStrGuardJsMain"))
        assertTrue(result.output.contains("kmp-prefix-runtime-sensitive-suffix"))
        val transformedClass = projectDirectory.resolve("build/strguard/classes/jvm/main/sample/MainKt.class")
        assertFalse(classContains(transformedClass, "sensitive-suffix"))
        val artifact = projectDirectory.resolve("build/libs/kmp-consumer-jvm.jar")
        JarFile(artifact.toFile()).use { jar ->
            val entry = assertNotNull(jar.getJarEntry("sample/MainKt.class"))
            val classText = jar.getInputStream(entry).readBytes().toString(StandardCharsets.ISO_8859_1)
            assertFalse(classText.contains("sensitive-suffix"))
            assertTrue(
                jar.entries().asSequence().any {
                    it.name.startsWith("META-INF/strguard/native/${nativeTarget.resourceDirectory}/") &&
                            it.name.endsWith(nativeTarget.libraryExtension)
                },
            )
        }
    }

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDirectory.toFile())
            .withArguments(*arguments, "--stacktrace")
            .forwardOutput()

    private fun writeFile(relativePath: String, contents: String) {
        val file = projectDirectory.resolve(relativePath)
        Files.createDirectories(file.parent)
        Files.writeString(file, contents, StandardCharsets.UTF_8)
    }

    private fun classContains(classFile: Path, value: String): Boolean =
        Files.readAllBytes(classFile).toString(StandardCharsets.ISO_8859_1).contains(value)

    private fun projectRootPath(): String =
        Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().toString().replace("\\", "\\\\")

    private fun hostNativeTarget(): NativeTarget =
        NativeTarget.detectHost(System.getProperty("os.name"), System.getProperty("os.arch"))
}

private const val KMP_TEST_SEED =
    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
