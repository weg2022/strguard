package io.github.weg2022.strguard

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.exists
import kotlin.test.*

class DisabledPluginFunctionalTest {
    @TempDir
    lateinit var projectDirectory: Path

    @Test
    fun `disabled plugin copies classes without seed target or Native toolchain`() {
        writeFile("settings.gradle.kts", "rootProject.name = \"disabled-consumer\"")
        writeFile(
            "build.gradle.kts",
            """
            plugins {
                java
                id("io.github.weg2022.strguard")
            }

            strGuard {
                enabled.set(false)
                removeMetadata.set(true)
                targetTriple.set("not-a-real-rust-target")
                consoleOutput.set(true)
            }
            """.trimIndent(),
        )
        writeFile(
            "src/main/java/sample/DisabledExample.java",
            """
            package sample;

            public final class DisabledExample {
                public static String reveal() {
                    return "disabled-sensitive-value";
                }
            }
            """.trimIndent(),
        )

        val result = runner("jar", "--info").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":transformStrGuardMain")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":buildStrGuardNativeMain")?.outcome)
        assertTrue(result.output.contains("protected 0 call sites"))
        val originalClass = projectDirectory.resolve("build/classes/java/main/sample/DisabledExample.class")
        val transformedClass = projectDirectory.resolve("build/strguard/classes/main/sample/DisabledExample.class")
        assertContentEquals(Files.readAllBytes(originalClass), Files.readAllBytes(transformedClass))
        assertEquals(
            "protectedStrings=0\nremovedMetadata=0\n",
            Files.readString(projectDirectory.resolve("build/reports/strguard/main/summary.txt")),
        )
        assertDirectoryEmpty(projectDirectory.resolve("build/strguard/native-input/main"))
        assertDirectoryEmpty(projectDirectory.resolve("build/strguard/native-resources/main"))

        val artifact = projectDirectory.resolve("build/libs/disabled-consumer.jar")
        JarFile(artifact.toFile()).use { jar ->
            val entry = assertNotNull(jar.getJarEntry("sample/DisabledExample.class"))
            val classText = jar.getInputStream(entry).readBytes().toString(StandardCharsets.ISO_8859_1)
            assertTrue(classText.contains("disabled-sensitive-value"))
            assertFalse(
                jar.entries().asSequence().any {
                    it.name.startsWith("io/github/weg2022/strguard/generated/") ||
                            it.name.startsWith("META-INF/strguard/native/")
                },
            )
        }
    }

    private fun assertDirectoryEmpty(directory: Path) {
        assertTrue(directory.exists())
        Files.list(directory).use { files -> assertEquals(0L, files.count()) }
    }

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDirectory.toFile())
            .withPluginClasspath()
            .withArguments(*arguments, "--stacktrace")
            .forwardOutput()

    private fun writeFile(relativePath: String, contents: String) {
        val file = projectDirectory.resolve(relativePath)
        Files.createDirectories(file.parent)
        Files.writeString(file, contents, StandardCharsets.UTF_8)
    }
}
