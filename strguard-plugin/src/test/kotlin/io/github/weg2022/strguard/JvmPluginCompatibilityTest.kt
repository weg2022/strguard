package io.github.weg2022.strguard

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.test.*

class JvmPluginCompatibilityTest {
    @TempDir
    lateinit var projectDirectory: Path

    @ParameterizedTest
    @ValueSource(strings = ["java", "java-library", "application"])
    fun `supports Java ecosystem plugin`(pluginId: String) {
        val nativeTarget = hostNativeTarget()
        writeFile("settings.gradle.kts", "rootProject.name = \"consumer-${pluginId.replace('-', '_')}\"")
        writeFile(
            "build.gradle.kts",
            """
            plugins {
                id("$pluginId")
                id("io.github.weg2022.strguard")
            }

            strGuard {
                releaseSeedHex.set("$JVM_COMPATIBILITY_TEST_SEED")
                targetTriple.set("${nativeTarget.rustTriple}")
                stringGuardPackages.set(listOf("sample"))
            }
            """.trimIndent(),
        )
        writeFile(
            "src/main/java/sample/Compatibility.java",
            """
            package sample;

            public final class Compatibility {
                public static String reveal() {
                    return "plugin-$pluginId-sensitive-value";
                }
            }
            """.trimIndent(),
        )

        val result = runner("jar").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":transformStrGuardMain")?.outcome)
        val transformedClasses = projectDirectory.resolve("build/strguard/classes/main")
        val nativeResources = projectDirectory.resolve("build/strguard/native-resources/main")
        assertFalse(classContains(transformedClasses.resolve("sample/Compatibility.class"), "sensitive-value"))
        URLClassLoader(
            arrayOf(transformedClasses.toUri().toURL(), nativeResources.toUri().toURL()),
            ClassLoader.getPlatformClassLoader(),
        ).use { loader ->
            val type = Class.forName("sample.Compatibility", true, loader)
            assertEquals("plugin-$pluginId-sensitive-value", type.getMethod("reveal").invoke(null))
        }

        val artifact = Files.list(projectDirectory.resolve("build/libs")).use { files ->
            files.iterator().asSequence().single { it.fileName.toString().endsWith(".jar") }
        }
        JarFile(artifact.toFile()).use { jar ->
            val entry = assertNotNull(jar.getJarEntry("sample/Compatibility.class"))
            val classText = jar.getInputStream(entry).readBytes().toString(StandardCharsets.ISO_8859_1)
            assertFalse(classText.contains("sensitive-value"))
            assertTrue(
                jar.entries().asSequence().any {
                    it.name.startsWith("META-INF/strguard/native/${nativeTarget.packagingDirectory}/") &&
                        it.name.endsWith(nativeTarget.libraryExtension)
                },
            )
        }
    }

    private fun runner(vararg arguments: String): GradleRunner = GradleRunner.create()
        .withProjectDir(projectDirectory.toFile())
        .withPluginClasspath()
        .withArguments(*arguments, "--stacktrace")
        .forwardOutput()

    private fun writeFile(relativePath: String, contents: String) {
        val file = projectDirectory.resolve(relativePath)
        Files.createDirectories(file.parent)
        Files.writeString(file, contents, StandardCharsets.UTF_8)
    }

    private fun classContains(classFile: Path, value: String): Boolean = Files.readAllBytes(classFile).toString(StandardCharsets.ISO_8859_1).contains(value)

    private fun hostNativeTarget(): JvmNativeTarget = JvmNativeTarget.detectHost(System.getProperty("os.name"), System.getProperty("os.arch"))
}

private const val JVM_COMPATIBILITY_TEST_SEED =
    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
