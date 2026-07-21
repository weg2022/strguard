package io.github.weg2022.strguard

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Java11RuntimeFunctionalTest {
    @TempDir
    lateinit var projectDirectory: Path

    @Test
    fun `protected Java 11 artifact loads on a Java 11 runtime`() {
        val javaHome = System.getenv("STRGUARD_JAVA11_HOME")?.let(Path::of)
        assumeTrue(javaHome != null && Files.isDirectory(javaHome), "STRGUARD_JAVA11_HOME is not configured")
        val requiredJavaHome = requireNotNull(javaHome)
        writeFile("settings.gradle.kts", "rootProject.name = \"java11-runtime\"")
        writeFile(
            "build.gradle.kts",
            """
            plugins {
                application
                id("io.github.weg2022.strguard")
            }

            application { mainClass.set("sample.Java11Example") }
            tasks.compileJava { options.release.set(11) }
            strGuard { releaseSeedHex.set("$JAVA11_TEST_SEED") }
            """.trimIndent(),
        )
        writeFile(
            "src/main/java/sample/Java11Example.java",
            """
            package sample;

            public final class Java11Example {
                private static String first() { return "java11-shared-identity"; }
                private static String second() { return "java11-shared-identity"; }
                private static String special() { return "prefix\0\uD800middle\uDC00\uD83D\uDE00suffix"; }

                public static void main(String[] args) {
                    if (first() != second()) throw new AssertionError("literal identity changed");
                    if (special().length() != 23) throw new AssertionError("UTF-16 code units changed");
                    System.out.println("STRGUARD_JAVA11_OK");
                }
            }
            """.trimIndent(),
        )

        GradleRunner.create()
            .withProjectDir(projectDirectory.toFile())
            .withPluginClasspath()
            .withArguments("jar", "--stacktrace")
            .forwardOutput()
            .build()

        val jar =
            Files.list(projectDirectory.resolve("build/libs")).use { files ->
                files.iterator().asSequence().single { it.fileName.toString().endsWith(".jar") }
            }
        val javaExecutable =
            requiredJavaHome.resolve("bin").resolve(if (isWindows()) "java.exe" else "java")
        val process =
            ProcessBuilder(javaExecutable.toString(), "-cp", jar.toString(), "sample.Java11Example")
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Java 11 runtime process timed out")
        assertEquals(0, process.exitValue(), output)
        assertTrue(output.contains("STRGUARD_JAVA11_OK"), output)
    }

    private fun writeFile(relativePath: String, contents: String) {
        val file = projectDirectory.resolve(relativePath)
        Files.createDirectories(file.parent)
        Files.writeString(file, contents, StandardCharsets.UTF_8)
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
}

private const val JAVA11_TEST_SEED =
    "789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456"
