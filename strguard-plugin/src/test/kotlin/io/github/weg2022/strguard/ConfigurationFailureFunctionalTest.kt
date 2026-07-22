package io.github.weg2022.strguard

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.*

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

        assertTrue(result.output.contains("Unsupported StrGuard JVM Native target 'not-a-rust-target'"))
        assertTrue(result.output.contains("x86_64-pc-windows-msvc"))
        assertTrue(result.output.contains("aarch64-pc-windows-msvc"))
    }

    @Test
    fun `illegal package selector reports the property and value`() {
        writeProject(
            """
            releaseSeedHex.set("$CONFIGURATION_FAILURE_TEST_SEED")
            stringGuardPackages.set(listOf("sample..internal"))
            """.trimIndent(),
        )

        val result = runner().buildAndFail()

        assertTrue(result.output.contains("stringGuardPackages"))
        assertTrue(result.output.contains("sample..internal"))
        assertTrue(result.output.contains("legal package segment"))
    }

    @Test
    fun `explicit include that matches no eligible class fails closed`() {
        writeProject(
            """
            releaseSeedHex.set("$CONFIGURATION_FAILURE_TEST_SEED")
            stringGuardPackages.set(listOf("missing.package"))
            """.trimIndent(),
        )

        val result = runner().buildAndFail()

        assertTrue(
            result.output.contains(
                "stringGuardPackages selector 'missing/package' did not match any eligible class",
            ),
        )
    }

    @Test
    fun `explicit include matching a class with no string literal succeeds`() {
        writeProject(
            """
            releaseSeedHex.set("$CONFIGURATION_FAILURE_TEST_SEED")
            stringGuardPackages.set(listOf("sample.empty"))
            removeMetadata.set(true)
            removeMetadataPackages.set(listOf("sample.empty"))
            """.trimIndent(),
        )
        writeFile(
            "src/main/java/sample/empty/NoLiteral.java",
            """
            package sample.empty;

            public final class NoLiteral {
                public static int value() {
                    return 42;
                }
            }
            """.trimIndent(),
        )

        runner().build()

        val summary = readSummary()
        assertEquals("2", summary.getProperty("inputClasses"))
        assertEquals("2", summary.getProperty("eligibleClasses"))
        assertEquals("1", summary.getProperty("matchedClasses"))
        assertEquals("1", summary.getProperty("skippedClasses"))
        assertEquals("0", summary.getProperty("protectedStrings"))
        assertEquals("0", summary.getProperty("removedMetadata"))
    }

    @Test
    fun `unmatched keep selector is warned and recorded`() {
        writeProject(
            """
            releaseSeedHex.set("$CONFIGURATION_FAILURE_TEST_SEED")
            keepStringPackages.set(listOf("missing.keep"))
            removeMetadata.set(true)
            keepMetadataPackages.set(listOf("missing.metadata"))
            """.trimIndent(),
        )

        val result = runner().build()

        assertTrue(
            result.output.contains(
                "keepStringPackages selector 'missing/keep' did not match any eligible class",
            ),
        )
        assertTrue(
            result.output.contains(
                "keepMetadataPackages selector 'missing/metadata' did not match any eligible class",
            ),
        )
        assertEquals(
            "missing/keep",
            readSummary().getProperty("unmatchedKeepStringPackages"),
        )
        assertEquals(
            "missing/metadata",
            readSummary().getProperty("unmatchedKeepMetadataPackages"),
        )
    }

    @Test
    fun `strict string coverage writes aggregate report before failing`() {
        writeProject(
            """
            releaseSeedHex.set("$CONFIGURATION_FAILURE_TEST_SEED")
            strictStringCoverage.set(true)
            stringGuardPackages.set(listOf("sample"))
            """.trimIndent(),
        )
        writeFile(
            "src/main/java/sample/Label.java",
            """
            package sample;

            public @interface Label {
                String value();
            }
            """.trimIndent(),
        )
        writeFile(
            "src/main/java/sample/AnnotatedValue.java",
            """
            package sample;

            @Label("annotation-sensitive-value")
            public final class AnnotatedValue {}
            """.trimIndent(),
        )

        val result = runner().buildAndFail()

        assertContains(result.output, "skippedAnnotationStrings=1")
        assertContains(result.output, "strictStringCoverage is enabled")
        val summary = readSummary()
        assertEquals("true", summary.getProperty("strictStringCoverage"))
        assertEquals("1", summary.getProperty("skippedAnnotationStrings"))
        assertEquals("1", summary.getProperty("strictViolations"))
        assertNoRegularFiles(projectDirectory.resolve("build/strguard/classes/main"))
        assertNoRegularFiles(projectDirectory.resolve("build/strguard/native-input/main"))
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

    private fun runner(): GradleRunner = GradleRunner.create()
        .withProjectDir(projectDirectory.toFile())
        .withPluginClasspath()
        .withArguments("transformStrGuardMain", "--stacktrace")
        .forwardOutput()

    private fun readSummary(): Properties = Properties().apply {
        Files.newBufferedReader(
            projectDirectory.resolve("build/reports/strguard/main/summary.txt"),
            StandardCharsets.UTF_8,
        ).use(::load)
    }

    private fun assertNoRegularFiles(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { files ->
            assertFalse(files.anyMatch(Files::isRegularFile))
        }
    }

    private fun writeFile(relativePath: String, contents: String) {
        val file = projectDirectory.resolve(relativePath)
        Files.createDirectories(file.parent)
        Files.writeString(file, contents, StandardCharsets.UTF_8)
    }
}

private const val CONFIGURATION_FAILURE_TEST_SEED =
    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
