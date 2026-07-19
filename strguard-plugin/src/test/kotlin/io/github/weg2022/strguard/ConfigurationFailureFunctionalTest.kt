package io.github.weg2022.strguard

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class ConfigurationFailureFunctionalTest {
    @TempDir
    lateinit var projectDirectory: Path

    @Test
    fun `missing release seed reports both supported configuration sources`() {
        writeProject("")

        val result = runner().buildAndFail()

        assertTrue(
            result.output.contains(
                "requires strGuard.releaseSeedHex or STRGUARD_RELEASE_SEED_HEX",
            ),
        )
    }

    @Test
    fun `invalid release seed reports exact format`() {
        writeProject("releaseSeedHex.set(\"not-a-hex-seed\")")

        val result = runner().buildAndFail()

        assertTrue(result.output.contains("must contain exactly 64 hexadecimal characters"))
    }

    @Test
    fun `unknown Native target lists supported targets`() {
        writeProject(
            """
            releaseSeedHex.set("$CONFIGURATION_FAILURE_TEST_SEED")
            targetTriple.set("not-a-rust-target")
            """.trimIndent(),
        )

        val result = runner().buildAndFail()

        assertTrue(result.output.contains("Unsupported StrGuard Native target 'not-a-rust-target'"))
        assertTrue(result.output.contains("x86_64-pc-windows-msvc"))
        assertTrue(result.output.contains("aarch64-linux-android"))
    }

    private fun writeProject(strGuardConfiguration: String) {
        writeFile("settings.gradle.kts", "rootProject.name = \"configuration-failure\"")
        writeFile(
            "build.gradle.kts",
            """
            plugins {
                java
                id("io.github.weg2022.strguard")
            }

            strGuard {
                $strGuardConfiguration
            }
            """.trimIndent(),
        )
        writeFile(
            "src/main/java/sample/ProtectedValue.java",
            """
            package sample;

            public final class ProtectedValue {
                public static String reveal() {
                    return "configuration-failure-sensitive-value";
                }
            }
            """.trimIndent(),
        )
    }

    private fun runner(): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDirectory.toFile())
            .withPluginClasspath()
            .withArguments("transformStrGuardMain", "--stacktrace")
            .forwardOutput()

    private fun writeFile(relativePath: String, contents: String) {
        val file = projectDirectory.resolve(relativePath)
        Files.createDirectories(file.parent)
        Files.writeString(file, contents, StandardCharsets.UTF_8)
    }
}

private const val CONFIGURATION_FAILURE_TEST_SEED =
    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
