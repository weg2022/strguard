package io.github.weg2022.strguard

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

class StrGuardBuildCacheFunctionalTest {
    @TempDir
    lateinit var projectDirectory: Path

    @Test
    fun `unchanged consumer is up to date and clean outputs are restored from Build Cache`() {
        writeSettingsFile(projectDirectory, "local-build-cache")
        writeBuildFile(BUILD_CACHE_TEST_SEED)
        writeSourceFile(projectDirectory)

        val first = runner("clean", "classes", "--build-cache", "--info").build()
        assertEquals(TaskOutcome.SUCCESS, first.task(":transformStrGuardMain")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, first.task(":buildStrGuardNativeMain")?.outcome)
        val expectedDigest = digestStrGuardOutputs()
        assertOutputsDoNotContain(BUILD_CACHE_TEST_SEED)

        val unchanged = runner("classes", "--build-cache", "--info").build()
        assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":transformStrGuardMain")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":buildStrGuardNativeMain")?.outcome)

        projectDirectory.resolve("build/strguard").toFile().deleteRecursively()
        projectDirectory.resolve("build/reports/strguard").toFile().deleteRecursively()

        val restored = runner("classes", "--build-cache", "--info").build()

        assertEquals(TaskOutcome.FROM_CACHE, restored.task(":transformStrGuardMain")?.outcome)
        assertEquals(TaskOutcome.FROM_CACHE, restored.task(":buildStrGuardNativeMain")?.outcome)
        assertContentEquals(expectedDigest, digestStrGuardOutputs())
        assertOutputsDoNotContain(BUILD_CACHE_TEST_SEED)

        val relocatedProject = projectDirectory.resolve("relocated")
        writeSettingsFile(relocatedProject, "../local-build-cache")
        writeBuildFile(BUILD_CACHE_TEST_SEED, relocatedProject)
        writeSourceFile(relocatedProject)
        val relocated = runner(relocatedProject, "classes", "--build-cache", "--info").build()
        assertEquals(TaskOutcome.FROM_CACHE, relocated.task(":transformStrGuardMain")?.outcome)
        assertEquals(TaskOutcome.FROM_CACHE, relocated.task(":buildStrGuardNativeMain")?.outcome)

        writeFile(
            ".cargo/config.toml",
            """
            [build]
            jobs = 1
            """.trimIndent(),
        )
        val cargoConfigurationChanged = runner("classes", "--build-cache", "--info").build()
        assertEquals(TaskOutcome.UP_TO_DATE, cargoConfigurationChanged.task(":transformStrGuardMain")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, cargoConfigurationChanged.task(":buildStrGuardNativeMain")?.outcome)
        assertContains(cargoConfigurationChanged.output, "External Cargo configuration may select untracked build tools")

        writeBuildFile(ALTERNATE_BUILD_CACHE_TEST_SEED)
        val seedChanged = runner("classes", "--build-cache", "--info").build()

        assertEquals(TaskOutcome.SUCCESS, seedChanged.task(":transformStrGuardMain")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, seedChanged.task(":buildStrGuardNativeMain")?.outcome)
        assertFalse(expectedDigest.contentEquals(digestStrGuardOutputs()))
        assertOutputsDoNotContain(ALTERNATE_BUILD_CACHE_TEST_SEED)
    }

    @Test
    fun `Cargo config above an external task workspace disables Native cache reuse`() {
        val consumer = projectDirectory.resolve("consumer")
        val externalBuild = projectDirectory.resolve("external-build")
        writeSettingsFile(consumer, "local-build-cache")
        writeFile(
            consumer,
            "build.gradle.kts",
            """
            plugins {
                java
                id("io.github.weg2022.strguard")
            }

            layout.buildDirectory.set(file("${externalBuild.toString().replace("\\", "\\\\")}"))

            strGuard {
                releaseSeedHex.set("$BUILD_CACHE_TEST_SEED")
                stringGuardPackages.set(listOf("sample"))
            }
            """.trimIndent(),
        )
        writeSourceFile(consumer)
        writeFile(
            externalBuild,
            "tmp/.cargo/config.toml",
            """
            [build]
            jobs = 1
            """.trimIndent(),
        )

        val result = runner(consumer, "classes", "--build-cache", "--info").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":buildStrGuardNativeMain")?.outcome)
        assertContains(result.output, "External Cargo configuration may select untracked build tools")

        val repeated = runner(consumer, "classes", "--build-cache", "--info").build()
        assertEquals(TaskOutcome.SUCCESS, repeated.task(":buildStrGuardNativeMain")?.outcome)
        assertContains(repeated.output, "External Cargo configuration may select untracked build tools")
    }

    @Test
    fun `toolchain probe resolves rust-toolchain above an external task workspace`() {
        val consumer = projectDirectory.resolve("consumer")
        val externalBuild = projectDirectory.resolve("external-build")
        writeSettingsFile(consumer, "local-build-cache")
        writeFile(
            consumer,
            "build.gradle.kts",
            """
            plugins {
                java
                id("io.github.weg2022.strguard")
            }

            layout.buildDirectory.set(file("${externalBuild.toString().replace("\\", "\\\\")}"))

            strGuard {
                releaseSeedHex.set("$BUILD_CACHE_TEST_SEED")
                stringGuardPackages.set(listOf("sample"))
            }
            """.trimIndent(),
        )
        writeSourceFile(consumer)
        writeFile(
            externalBuild,
            "tmp/rust-toolchain.toml",
            "this is deliberately malformed TOML",
        )

        val failure = runner(consumer, "buildStrGuardNativeMain", "--build-cache", "--info").buildAndFail()

        assertContains(failure.output, "version probe failed")
        assertContains(failure.output, "rust-toolchain.toml")
    }

    private fun digestStrGuardOutputs(): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(
            projectDirectory.resolve("build/strguard"),
            projectDirectory.resolve("build/reports/strguard"),
        ).forEach { root ->
            Files.walk(root).use { files ->
                files.filter(Files::isRegularFile).sorted().forEach { file ->
                    val relative = projectDirectory.relativize(file).toString().replace('\\', '/')
                    digest.update(relative.toByteArray(StandardCharsets.UTF_8))
                    digest.update(Files.readAllBytes(file))
                }
            }
        }
        return digest.digest()
    }

    private fun assertOutputsDoNotContain(forbidden: String) {
        val found =
            listOf(
                projectDirectory.resolve("build/strguard"),
                projectDirectory.resolve("build/reports/strguard"),
            ).any { root ->
                Files.walk(root).use { files ->
                    files.filter(Files::isRegularFile).anyMatch { file ->
                        Files.readAllBytes(file).toString(StandardCharsets.ISO_8859_1).contains(forbidden)
                    }
                }
            }
        assertFalse(found, "StrGuard task outputs contain the release seed")
    }

    private fun runner(vararg arguments: String): GradleRunner = runner(projectDirectory, *arguments)

    private fun runner(root: Path, vararg arguments: String): GradleRunner = GradleRunner.create()
        .withProjectDir(root.toFile())
        .withPluginClasspath()
        .withArguments(*arguments, "--stacktrace")
        .forwardOutput()

    private fun writeSettingsFile(root: Path, cachePath: String) {
        writeFile(
            root,
            "settings.gradle.kts",
            """
            rootProject.name = "cache-consumer"

            buildCache {
                local {
                    directory = file("$cachePath")
                }
            }
            """.trimIndent(),
        )
    }

    private fun writeBuildFile(seed: String, root: Path = projectDirectory) {
        writeFile(
            root,
            "build.gradle.kts",
            """
            plugins {
                java
                id("io.github.weg2022.strguard")
            }

            strGuard {
                releaseSeedHex.set("$seed")
                stringGuardPackages.set(listOf("sample"))
            }
            """.trimIndent(),
        )
    }

    private fun writeSourceFile(root: Path) {
        writeFile(
            root,
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
    }

    private fun writeFile(relativePath: String, contents: String) = writeFile(projectDirectory, relativePath, contents)

    private fun writeFile(root: Path, relativePath: String, contents: String) {
        val file = root.resolve(relativePath)
        Files.createDirectories(file.parent)
        Files.writeString(file, contents, StandardCharsets.UTF_8)
    }
}

private const val BUILD_CACHE_TEST_SEED =
    "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
private const val ALTERNATE_BUILD_CACHE_TEST_SEED =
    "123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0"
