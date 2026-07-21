package io.github.weg2022.strguard

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.compose.desktop.application.tasks.AbstractProguardTask
import java.nio.file.Files

internal object ComposeDesktopAdapter {
    fun configure(
        project: Project,
        extension: StrGuardExtension,
        supportClasses: TaskProvider<PrepareSupportClassesTask>,
    ) {
        val rules = supportClasses.flatMap { task -> task.outputDirectory.file(STRGUARD_SHRINKER_RULES_FILE_NAME) }
        project.tasks.withType(AbstractProguardTask::class.java).configureEach { task ->
            task.configurationFiles.from(rules)
            task.doFirst {
                if (!extension.enabled.get()) return@doFirst
                val mainJar = task.mainJar.get().asFile.toPath()
                if (!StrGuardShrunkArtifactFinalizer.hasProtectedMarker(mainJar)) {
                    throw GradleException(
                        "Compose Desktop ProGuard task ${task.path} does not consume the StrGuard protected JVM JAR",
                    )
                }
                protectedInputJars(task).forEach(StrGuardShrunkArtifactFinalizer::readProtectedMetadata)
            }
            task.doLast {
                if (!extension.enabled.get()) return@doLast
                val protectedArtifacts =
                    protectedInputJars(task).map { protectedJar ->
                        protectedJar to StrGuardShrunkArtifactFinalizer.readProtectedMetadata(protectedJar)
                    }
                val outputDirectory = task.destinationDir.get().asFile.toPath()
                val outputJars =
                    if (Files.isDirectory(outputDirectory)) {
                        Files.walk(outputDirectory).use { paths ->
                            paths.iterator().asSequence().filter { path ->
                                Files.isRegularFile(path) && path.fileName.toString().endsWith(".jar")
                            }.toList()
                        }
                    } else {
                        emptyList()
                    }
                protectedArtifacts.forEach { (protectedJar, metadata) ->
                    val candidates =
                        outputJars.filter { outputJar ->
                            StrGuardShrunkArtifactFinalizer.containsClass(outputJar, metadata.bridgeClass)
                        }
                    if (candidates.size != 1) {
                        throw GradleException(
                            "Compose Desktop ProGuard task ${task.path} produced ${candidates.size} JARs " +
                                "containing StrGuard bridge ${metadata.bridgeClass}; expected exactly one",
                        )
                    }
                    StrGuardShrunkArtifactFinalizer.finalize(
                        protectedJar = protectedJar,
                        shrunkJar = candidates.single(),
                        verifiedJar = candidates.single(),
                        shrinkerId = "compose-desktop-proguard",
                    )
                }
            }
        }
    }

    private fun protectedInputJars(task: AbstractProguardTask): List<java.nio.file.Path> = (
        sequenceOf(task.mainJar.get().asFile.toPath()) +
            task.inputFiles.files.asSequence().map { file -> file.toPath() }
        )
        .filter { path ->
            Files.isRegularFile(path) &&
                path.fileName.toString().endsWith(".jar") &&
                StrGuardShrunkArtifactFinalizer.hasProtectedMarker(path)
        }.distinct()
        .toList()
}
