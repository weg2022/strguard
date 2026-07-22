package io.github.weg2022.strguard

import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
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
        artifacts: StrGuardArtifactsExtension,
        supportClasses: TaskProvider<PrepareSupportClassesTask>,
        processRegistry: Provider<NativeProcessRegistryService>,
    ) {
        val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
        kotlin.targets.configureEach { target ->
            if (target is KotlinJvmTarget) {
                target.compilations.named(KotlinCompilation.MAIN_COMPILATION_NAME).configure { compilation ->
                    configureJvmTarget(
                        project,
                        extension,
                        artifacts,
                        supportClasses,
                        processRegistry,
                        target,
                        compilation,
                    )
                }
            } else {
                project.logger.lifecycle(
                    "StrGuard pass-through: Kotlin Multiplatform target '${target.name}' is not a JVM target; " +
                        "no transform or Native task is registered.",
                )
            }
        }
    }

    private fun configureJvmTarget(
        project: Project,
        extension: StrGuardExtension,
        artifacts: StrGuardArtifactsExtension,
        supportClasses: TaskProvider<PrepareSupportClassesTask>,
        processRegistry: Provider<NativeProcessRegistryService>,
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
            task.group = STRGUARD_TASK_GROUP
            task.description = "Transforms the ${target.name} Kotlin Multiplatform JVM classes with StrGuard."
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
            task.releaseSeedHex.convention(project.releaseSeed(extension))
            task.releaseSeedFingerprint.convention(project.releaseSeedFingerprint(extension))
            task.moduleIdentity.convention("${project.group}:${project.name}:${project.path}:${target.name}")
            task.targetTriple.convention(
                project.strGuardProvider(
                    enabled = extension.enabled,
                    enabledValue = extension.targetTriple,
                    disabledValue = DISABLED_STRGUARD_VALUE,
                ),
            )
            task.java9StringConcatEnabled.convention(extension.java9StringConcatEnabled)
            task.strictStringCoverage.convention(extension.strictStringCoverage)
            task.consoleOutput.convention(extension.consoleOutput)
            task.removeMetadata.convention(extension.removeMetadata)
            task.stringGuardPackages.convention(
                project.strGuardPackageSelectors(extension, extension.stringGuardPackages, "stringGuardPackages"),
            )
            task.keepStringPackages.convention(
                project.strGuardPackageSelectors(extension, extension.keepStringPackages, "keepStringPackages"),
            )
            task.removeMetadataPackages.convention(
                project.strGuardPackageSelectors(extension, extension.removeMetadataPackages, "removeMetadataPackages"),
            )
            task.keepMetadataPackages.convention(
                project.strGuardPackageSelectors(extension, extension.keepMetadataPackages, "keepMetadataPackages"),
            )
        }

        val nativeTask =
            project.tasks.register(
                "buildStrGuard${targetName}Native",
                BuildNativeRuntimeTask::class.java,
            )
        nativeTask.configure { task ->
            task.group = STRGUARD_TASK_GROUP
            task.description = "Builds the StrGuard Native runtime for the ${target.name} JVM target."
            task.dependsOn(transformTask)
            task.nativeInputDirectory.convention(transformTask.flatMap { it.nativeInputDirectory })
            task.outputDirectory.convention(
                project.layout.buildDirectory.dir("strguard/native-resources/${target.name}/main"),
            )
            task.nativeEnabled.convention(extension.enabled)
            task.targetTriple.convention(
                project.strGuardProvider(
                    enabled = extension.enabled,
                    enabledValue = extension.targetTriple,
                    disabledValue = DISABLED_STRGUARD_VALUE,
                ),
            )
            task.cargoExecutable.convention(
                project.strGuardProvider(
                    enabled = extension.enabled,
                    enabledValue =
                    project.providers.environmentVariable(CARGO_EXECUTABLE_ENVIRONMENT_VARIABLE)
                        .orElse("cargo"),
                    disabledValue = DISABLED_STRGUARD_VALUE,
                ),
            )
            task.runtimeTemplateVersion.convention("4")
            task.processTimeoutSeconds.convention(DEFAULT_NATIVE_PROCESS_TIMEOUT_SECONDS)
            task.externalCargoConfigurationPresent.convention(
                project.nativeCargoConfigurationFiles(task).elements.map { files ->
                    files.any { file -> file.asFile.isFile }
                },
            )
            task.toolchainFingerprint.convention(
                project.strGuardProvider(
                    enabled = extension.enabled,
                    enabledValue =
                    project.providers.of(NativeToolchainFingerprintValueSource::class.java) { spec ->
                        spec.parameters.cargoExecutable.set(task.cargoExecutable)
                        spec.parameters.targetTriple.set(extension.targetTriple)
                        spec.parameters.captureBuildEnvironment(project, task)
                    },
                    disabledValue = DISABLED_STRGUARD_VALUE,
                ),
            )
            task.processRegistry.set(processRegistry)
            task.usesService(processRegistry)
        }

        val transformedClasses = project.files(transformTask.flatMap { it.outputDirectory })
        val nativeResources = project.files(nativeTask.flatMap { it.outputDirectory })
        project.tasks.named(target.artifactsTaskName, Jar::class.java).configure { jarTask ->
            jarTask.dependsOn(nativeTask)
            jarTask.isPreserveFileTimestamps = false
            jarTask.isReproducibleFileOrder = true
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
        artifacts.register(
            targetName = target.name,
            protectedJar = project.tasks.named(target.artifactsTaskName, Jar::class.java).flatMap { it.archiveFile },
            requiredShrinkerRules = supportClasses.flatMap { it.outputDirectory.file(STRGUARD_SHRINKER_RULES_FILE_NAME) },
        )
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
