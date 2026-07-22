package io.github.weg2022.strguard

import org.gradle.testfixtures.ProjectBuilder
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
    fun `conditional Provider does not query the disabled branch`() {
        val project = ProjectBuilder.builder().build()
        var enabledValueQueries = 0
        val value =
            strGuardProvider(
                enabled = project.providers.provider { false },
                enabledValue =
                project.providers.provider {
                    enabledValueQueries++
                    "enabled"
                },
                disabledValue = project.providers.provider { DISABLED_STRGUARD_VALUE },
            )

        assertEquals(DISABLED_STRGUARD_VALUE, value.get())
        assertEquals(0, enabledValueQueries)
    }

    @Test
    fun `disabled plugin copies classes without seed target or Native toolchain`() {
        writeFile("settings.gradle.kts", "rootProject.name = \"disabled-consumer\"")
        writeFile(
            "build.gradle.kts",
            """
            import io.github.weg2022.strguard.BuildNativeRuntimeTask
            import io.github.weg2022.strguard.TransformClassesTask

            plugins {
                java
                id("io.github.weg2022.strguard")
            }

            strGuard {
                enabled.set(false)
                removeMetadata.set(true)
                releaseSeedHex.set(providers.provider<String> {
                    error("disabled release seed Provider was evaluated")
                })
                targetTriple.set(providers.provider<String> {
                    error("disabled target Provider was evaluated")
                })
                stringGuardPackages.set(providers.provider<List<String>> {
                    error("disabled string selector Provider was evaluated")
                })
                keepStringPackages.set(providers.provider<List<String>> {
                    error("disabled keep selector Provider was evaluated")
                })
                consoleOutput.set(true)
            }

            tasks.register("verifyStrGuardTaskMetadata") {
                doLast {
                    listOf(
                        "prepareStrGuardSupportClasses",
                        "transformStrGuardMain",
                        "buildStrGuardNativeMain",
                    ).forEach { taskName ->
                        val publicTask = project.tasks.getByName(taskName)
                        check(publicTask.group == "strguard") { "${'$'}taskName has no StrGuard task group" }
                        check(!publicTask.description.isNullOrBlank()) { "${'$'}taskName has no description" }
                    }
                    project.tasks.named<TransformClassesTask>("transformStrGuardMain").get().let { task ->
                        check(task.releaseSeedHex.get() == "disabled")
                        check(task.releaseSeedFingerprint.get() == "disabled")
                        check(task.targetTriple.get() == "disabled")
                    }
                    project.tasks.named<BuildNativeRuntimeTask>("buildStrGuardNativeMain").get().let { task ->
                        check(task.targetTriple.get() == "disabled")
                        check(task.cargoExecutable.get() == "disabled")
                    }
                }
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

        val result = runner("jar", "verifyStrGuardTaskMetadata", "--info").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":transformStrGuardMain")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":buildStrGuardNativeMain")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyStrGuardTaskMetadata")?.outcome)
        assertTrue(result.output.contains("protected 0 string locations, skipped 0"))
        val originalClass = projectDirectory.resolve("build/classes/java/main/sample/DisabledExample.class")
        val transformedClass = projectDirectory.resolve("build/strguard/classes/main/sample/DisabledExample.class")
        assertContentEquals(Files.readAllBytes(originalClass), Files.readAllBytes(transformedClass))
        assertEquals(
            """
            schemaVersion=1
            enabled=false
            strictStringCoverage=false
            runtimeTarget=disabled
            inputClasses=1
            eligibleClasses=0
            matchedClasses=0
            skippedClasses=0
            stringCandidates=0
            protectedStrings=0
            skippedStrings=0
            strictViolations=0
            coverageUnknowns=0
            skippedEmptyStrings=0
            skippedOversizedStrings=0
            skippedAnnotationStrings=0
            skippedConstantDynamicStrings=0
            skippedDisabledStringConcats=0
            skippedUnsupportedStringConcats=0
            skippedUnsupportedInvokeDynamics=0
            skippedUnsupportedFieldStrings=0
            removedMetadata=0
            unmatchedKeepStringPackages=
            unmatchedKeepMetadataPackages=

            """.trimIndent(),
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
}
