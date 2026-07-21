package io.github.weg2022.strguard

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class NativeToolchainFailureFunctionalTest {
    @TempDir
    lateinit var projectDirectory: Path

    @Test
    fun `reports missing Cargo executable with target and recovery command`() {
        writeFile("settings.gradle.kts", "rootProject.name = \"missing-cargo\"")
        writeFile(
            "build.gradle.kts",
            """
            plugins {
                java
                id("io.github.weg2022.strguard")
            }

            strGuard {
                releaseSeedHex.set("$NATIVE_TOOLCHAIN_TEST_SEED")
                targetTriple.set("${hostNativeTarget().rustTriple}")
            }
            """.trimIndent(),
        )
        writeFile(
            "src/main/java/sample/MissingCargo.java",
            """
            package sample;

            public final class MissingCargo {
                public static String reveal() {
                    return "missing-cargo-sensitive-value";
                }
            }
            """.trimIndent(),
        )
        val emptyPath = Files.createDirectories(projectDirectory.resolve("empty-path")).toString()
        val environment = System.getenv().toMutableMap()
        environment.remove(CARGO_EXECUTABLE_ENVIRONMENT_VARIABLE)
        val pathKey = environment.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
        environment[pathKey] = emptyPath

        val result =
            GradleRunner.create()
                .withProjectDir(projectDirectory.toFile())
                .withPluginClasspath()
                .withEnvironment(environment)
                .withArguments("buildStrGuardNativeMain", "--stacktrace")
                .forwardOutput()
                .buildAndFail()

        assertTrue(result.output.contains("cannot start Cargo executable 'cargo'"))
        assertTrue(result.output.contains("target ${hostNativeTarget().rustTriple}"))
        assertTrue(result.output.contains("rustup target add ${hostNativeTarget().rustTriple}"))
    }

    private fun writeFile(relativePath: String, contents: String) {
        val file = projectDirectory.resolve(relativePath)
        Files.createDirectories(file.parent)
        Files.writeString(file, contents, StandardCharsets.UTF_8)
    }

    private fun hostNativeTarget(): JvmNativeTarget = JvmNativeTarget.detectHost(System.getProperty("os.name"), System.getProperty("os.arch"))
}

private const val NATIVE_TOOLCHAIN_TEST_SEED =
    "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
