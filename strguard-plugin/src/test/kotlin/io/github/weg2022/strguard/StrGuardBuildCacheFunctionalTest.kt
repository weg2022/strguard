package io.github.weg2022.strguard

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class StrGuardBuildCacheFunctionalTest {
    @TempDir
    lateinit var projectDirectory: Path

    @Test
    fun `transform outputs are never restored from Build Cache`() {
        writeFile(
            "settings.gradle.kts",
            """
            rootProject.name = "cache-consumer"

            buildCache {
                local {
                    directory = file("local-build-cache")
                }
            }
            """.trimIndent(),
        )
        writeFile(
            "build.gradle.kts",
            """
            plugins {
                java
                id("io.github.weg2022.strguard")
            }

            strGuard {
                releaseSeedHex.set("$BUILD_CACHE_TEST_SEED")
                stringGuardPackages.set(listOf("sample"))
            }
            """.trimIndent(),
        )
        writeFile(
            "src/main/java/sample/CacheExample.java",
            """
            package sample;

            public final class CacheExample {
                public static String reveal() {
                    return "cache-sensitive-value";
                }
            }
            """.trimIndent(),
        )

        val first = runner("transformStrGuardMain", "--build-cache", "--info").build()
        assertEquals(TaskOutcome.SUCCESS, first.task(":transformStrGuardMain")?.outcome)
        projectDirectory.resolve("build/strguard").toFile().deleteRecursively()
        projectDirectory.resolve("build/reports/strguard").toFile().deleteRecursively()

        val second = runner("transformStrGuardMain", "--build-cache", "--info").build()

        assertEquals(TaskOutcome.SUCCESS, second.task(":transformStrGuardMain")?.outcome)
        assertFalse(second.task(":transformStrGuardMain")?.outcome == TaskOutcome.FROM_CACHE)
        assertTrue(second.output.contains("Outputs contain build-specific seed-derived Native key material"))
        assertCacheDoesNotContain(BUILD_CACHE_TEST_SEED)
    }

    private fun assertCacheDoesNotContain(forbidden: String) {
        val cacheDirectory = projectDirectory.resolve("local-build-cache")
        val found = Files.walk(cacheDirectory).use { files ->
            files.filter(Files::isRegularFile).anyMatch { file ->
                Files.readAllBytes(file).toString(StandardCharsets.ISO_8859_1).contains(forbidden)
            }
        }
        assertFalse(found, "Gradle Build Cache contains the release seed")
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

private const val BUILD_CACHE_TEST_SEED =
    "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
