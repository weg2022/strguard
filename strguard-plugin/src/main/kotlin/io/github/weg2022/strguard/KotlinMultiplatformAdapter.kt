package io.github.weg2022.strguard

import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import java.nio.file.Files
import java.util.*

internal object KotlinMultiplatformAdapter {
    fun configure(
        project: Project,
        extension: StrGuardExtension,
        supportClasses: TaskProvider<PrepareSupportClassesTask>,
    ) {
        val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
        kotlin.targets.withType(KotlinJvmTarget::class.java).configureEach { target ->
            target.compilations.named(KotlinCompilation.MAIN_COMPILATION_NAME).configure { compilation ->
                configureJvmTarget(project, extension, supportClasses, target, compilation)
            }
        }
    }

    private fun configureJvmTarget(
        project: Project,
        extension: StrGuardExtension,
        supportClasses: TaskProvider<PrepareSupportClassesTask>,
        target: KotlinJvmTarget,
        compilation: KotlinCompilation<*>,
    ) {
        val targetName = target.name.replaceFirstChar { character ->
            if (character.isLowerCase()) character.titlecase(Locale.ROOT) else character.toString()
        }
        val supportClasspath = project.files(supportClasses.flatMap { it.outputDirectory })
        compilation.compileDependencyFiles = compilation.compileDependencyFiles + supportClasspath

        val transformTask =
            project.tasks.register(
                "transformStrGuard${targetName}Main",
                TransformClassesTask::class.java,
            )
        transformTask.configure { task ->
            task.inputClassDirectories.from(compilation.output.classesDirs)
            task.outputDirectory.convention(
                project.layout.buildDirectory.dir("strguard/classes/${target.name}/main"),
            )
            task.nativeInputDirectory.convention(
                project.layout.buildDirectory.dir("strguard/native-input/${target.name}/main"),
            )
            task.reportDirectory.convention(
                project.layout.buildDirectory.dir("reports/strguard/${target.name}/main"),
            )
            task.stringGuardEnabled.convention(extension.enabled)
            task.releaseSeedHex.convention(extension.releaseSeedHex)
            task.releaseSeedFingerprint.convention(project.releaseSeedFingerprint(extension))
            task.moduleIdentity.convention("${project.group}:${project.name}:${project.path}:${target.name}")
            task.targetTriple.convention(extension.targetTriple)
            task.java9StringConcatEnabled.convention(extension.java9StringConcatEnabled)
            task.consoleOutput.convention(extension.consoleOutput)
            task.removeMetadata.convention(extension.removeMetadata)
            task.stringGuardPackages.convention(extension.stringGuardPackages)
            task.keepStringPackages.convention(extension.keepStringPackages)
            task.removeMetadataPackages.convention(extension.removeMetadataPackages)
            task.keepMetadataPackages.convention(extension.keepMetadataPackages)
        }

        val nativeTask =
            project.tasks.register(
                "buildStrGuard${targetName}Native",
                BuildNativeRuntimeTask::class.java,
            )
        nativeTask.configure { task ->
            task.dependsOn(transformTask)
            task.nativeInputDirectory.convention(transformTask.flatMap { it.nativeInputDirectory })
            task.outputDirectory.convention(
                project.layout.buildDirectory.dir("strguard/native-resources/${target.name}/main"),
            )
            task.nativeEnabled.convention(extension.enabled)
            task.targetTriple.convention(extension.targetTriple)
            task.cargoExecutable.convention("cargo")
            task.runtimeTemplateVersion.convention("4")
        }

        val transformedClasses = project.files(transformTask.flatMap { it.outputDirectory })
        val nativeResources = project.files(nativeTask.flatMap { it.outputDirectory })
        project.tasks.named(target.artifactsTaskName, Jar::class.java).configure { jarTask ->
            jarTask.dependsOn(nativeTask)
            jarTask.exclude { element ->
                val source = element.file.toPath().toAbsolutePath().normalize()
                compilation.output.classesDirs.files.any { classDirectory ->
                    val root = classDirectory.toPath().toAbsolutePath().normalize()
                    Files.isDirectory(root) && source.startsWith(root)
                }
            }
            jarTask.from(transformedClasses)
            jarTask.from(nativeResources)
        }
        configureRunTask(project, "${target.name}Run", nativeTask, transformedClasses, nativeResources)
        project.tasks.withType(Test::class.java).matching { it.name == "${target.name}Test" }.configureEach { test ->
            test.dependsOn(nativeTask)
            test.classpath = transformedClasses + nativeResources + test.classpath
        }
    }

    private fun configureRunTask(
        project: Project,
        taskName: String,
        nativeTask: TaskProvider<BuildNativeRuntimeTask>,
        transformedClasses: FileCollection,
        nativeResources: FileCollection,
    ) {
        project.tasks.withType(JavaExec::class.java).matching { it.name == taskName }.configureEach { runTask ->
            runTask.dependsOn(nativeTask)
            runTask.classpath = transformedClasses + nativeResources + runTask.classpath
        }
    }
}
