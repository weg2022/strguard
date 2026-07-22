package io.github.weg2022.strguard

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AsmIsolationFunctionalTest {
    @TempDir
    lateinit var projectDirectory: Path

    @Test
    fun `published plugin relocates ASM and transforms with ASM 9_8 already loaded`() {
        val shadowJar = Path.of(assertNotNull(System.getProperty("strguard.shadowJar")))
        JarFile(shadowJar.toFile()).use { jar ->
            val entries = jar.entries().asSequence().map { entry -> entry.name }.toList()
            assertTrue(entries.contains("io/github/weg2022/strguard/internal/asm/ClassReader.class"))
            assertTrue(entries.contains("io/github/weg2022/strguard/internal/asm/commons/SimpleRemapper.class"))
            assertFalse(entries.any { entry -> entry.startsWith("org/objectweb/asm/") })
        }
        writeFile("settings.gradle.kts", "rootProject.name = \"asm-isolation-consumer\"")
        writeFile(
            "build.gradle.kts",
            """
            buildscript {
                repositories { mavenCentral() }
                dependencies {
                    classpath("org.ow2.asm:asm:9.8")
                    classpath("org.ow2.asm:asm-commons:9.8")
                }
            }

            plugins {
                java
                id("io.github.weg2022.strguard")
            }

            strGuard {
                releaseSeedHex.set("$ASM_ISOLATION_TEST_SEED")
                stringGuardPackages.set(listOf("sample"))
            }
            """.trimIndent(),
        )
        writeFile(
            "src/main/java/sample/IsolatedAsm.java",
            """
            package sample;

            public final class IsolatedAsm {
                public static String reveal() {
                    return "isolated-asm-sensitive-value";
                }
            }
            """.trimIndent(),
        )

        val result =
            GradleRunner.create()
                .withProjectDir(projectDirectory.toFile())
                .withPluginClasspath(listOf(shadowJar.toFile()))
                .withArguments("transformStrGuardMain", "--stacktrace")
                .forwardOutput()
                .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":transformStrGuardMain")?.outcome)
        val transformed =
            Files.readAllBytes(
                projectDirectory.resolve("build/strguard/classes/main/sample/IsolatedAsm.class"),
            ).toString(StandardCharsets.ISO_8859_1)
        assertFalse(transformed.contains("isolated-asm-sensitive-value"))
    }

    private fun writeFile(relativePath: String, contents: String) {
        val file = projectDirectory.resolve(relativePath)
        Files.createDirectories(file.parent)
        Files.writeString(file, contents, StandardCharsets.UTF_8)
    }
}

private const val ASM_ISOLATION_TEST_SEED =
    "456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123"
