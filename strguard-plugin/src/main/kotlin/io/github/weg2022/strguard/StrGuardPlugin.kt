package io.github.weg2022.strguard

import org.gradle.api.*
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile

class StrGuardPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("strGuard", StrGuardExtension::class.java)
        val artifacts =
            project.extensions.create(
                "strGuardArtifacts",
                StrGuardArtifactsExtension::class.java,
                project,
            )
        val processRegistry =
            project.gradle.sharedServices.registerIfAbsent(
                "strGuardNativeProcessRegistry",
                NativeProcessRegistryService::class.java,
            ) {}
        extension.releaseSeedHex.convention(
            project.strGuardProvider(
                enabled = extension.enabled,
                enabledValue = project.providers.environmentVariable("STRGUARD_RELEASE_SEED_HEX"),
                disabledValue = DISABLED_STRGUARD_VALUE,
            ),
        )
        val hostTarget =
            project.providers.systemProperty("os.name")
                .zip(project.providers.systemProperty("os.arch")) { osName, architecture ->
                    JvmNativeTarget.detectHost(osName, architecture).rustTriple
                }
        extension.targetTriple.convention(
            project.strGuardProvider(
                enabled = extension.enabled,
                enabledValue =
                project.providers.environmentVariable("STRGUARD_TARGET_TRIPLE").orElse(hostTarget),
                disabledValue = DISABLED_STRGUARD_VALUE,
            ),
        )
        val supportClasses: TaskProvider<PrepareSupportClassesTask> =
            project.tasks.register(
                "prepareStrGuardSupportClasses",
                PrepareSupportClassesTask::class.java,
            )
        supportClasses.configure(
            Action { task ->
                task.group = STRGUARD_TASK_GROUP
                task.description = "Prepares StrGuard compile-only support classes and shrinker rules."
                task.outputDirectory.convention(
                    project.layout.buildDirectory.dir("generated/strguard/compile-classpath"),
                )
            },
        )

        project.pluginManager.withPlugin("java") {
            configureJvmProject(project, extension, artifacts, supportClasses, processRegistry)
        }

        project.pluginManager.withPlugin("com.android.application") {
            AndroidAdapter.configureApplication(project, extension, supportClasses, processRegistry)
        }
        project.pluginManager.withPlugin("com.android.library") {
            AndroidAdapter.configureLibrary(project, extension, supportClasses, processRegistry)
        }
        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            KotlinMultiplatformAdapter.configure(project, extension, artifacts, supportClasses, processRegistry)
        }
        project.pluginManager.withPlugin("org.jetbrains.compose") {
            ComposeDesktopAdapter.configure(project, extension, supportClasses)
        }
    }

    private fun configureJvmProject(
        project: Project,
        extension: StrGuardExtension,
        artifacts: StrGuardArtifactsExtension,
        supportClasses: TaskProvider<PrepareSupportClassesTask>,
        processRegistry: Provider<NativeProcessRegistryService>,
    ) {
        val javaExtension = project.extensions.getByType(JavaPluginExtension::class.java)
        val mainSourceSet = javaExtension.sourceSets.named(SourceSet.MAIN_SOURCE_SET_NAME)
        val originalClassDirectories = project.objects.fileCollection()
        val compileJava = project.tasks.named(JavaPlugin.COMPILE_JAVA_TASK_NAME, JavaCompile::class.java)
        originalClassDirectories.from(compileJava.flatMap { it.destinationDirectory })

        // Kotlin/JVM creates this task after applying the Java plugin in some plugin orders.
        project.tasks.matching { it.name == "compileKotlin" }.configureEach { kotlinCompile ->
            originalClassDirectories.from(kotlinCompile.outputs.files)
        }

        val supportClasspath = project.files(supportClasses.flatMap { it.outputDirectory })
        mainSourceSet.configure { sourceSet ->
            sourceSet.compileClasspath += supportClasspath
        }

        val transformTask: TaskProvider<TransformClassesTask> =
            project.tasks.register(
                "transformStrGuardMain",
                TransformClassesTask::class.java,
            )
        transformTask.configure(
            Action { task ->
                task.group = STRGUARD_TASK_GROUP
                task.description = "Transforms main JVM classes with StrGuard."
                task.inputClassDirectories.from(originalClassDirectories)
                task.outputDirectory.convention(project.layout.buildDirectory.dir("strguard/classes/main"))
                task.nativeInputDirectory.convention(project.layout.buildDirectory.dir("strguard/native-input/main"))
                task.reportDirectory.convention(project.layout.buildDirectory.dir("reports/strguard/main"))
                task.stringGuardEnabled.convention(extension.enabled)
                task.releaseSeedHex.convention(project.releaseSeed(extension))
                task.releaseSeedFingerprint.convention(project.releaseSeedFingerprint(extension))
                task.moduleIdentity.convention("${project.group}:${project.name}:${project.path}")
                task.targetTriple.convention(
                    project.strGuardProvider(
                        enabled = extension.enabled,
                        enabledValue = extension.targetTriple,
                        disabledValue = DISABLED_STRGUARD_VALUE,
                    ),
                )
                task.java9StringConcatEnabled.convention(extension.java9StringConcatEnabled)
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
            },
        )

        val nativeTask: TaskProvider<BuildNativeRuntimeTask> =
            project.tasks.register(
                "buildStrGuardNativeMain",
                BuildNativeRuntimeTask::class.java,
            )
        nativeTask.configure(
            Action { task ->
                task.group = STRGUARD_TASK_GROUP
                task.description = "Builds the StrGuard Native runtime for main JVM classes."
                task.dependsOn(transformTask)
                task.nativeInputDirectory.convention(transformTask.flatMap { it.nativeInputDirectory })
                task.outputDirectory.convention(project.layout.buildDirectory.dir("strguard/native-resources/main"))
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
                task.toolchainFingerprint.convention(
                    project.strGuardProvider(
                        enabled = extension.enabled,
                        enabledValue =
                        project.providers.of(NativeToolchainFingerprintValueSource::class.java) { spec ->
                            spec.parameters.cargoExecutable.set(task.cargoExecutable)
                            spec.parameters.targetTriple.set(extension.targetTriple)
                        },
                        disabledValue = DISABLED_STRGUARD_VALUE,
                    ),
                )
                task.processRegistry.set(processRegistry)
                task.usesService(processRegistry)
            },
        )

        project.tasks.named(JavaPlugin.CLASSES_TASK_NAME).configure { classesTask ->
            classesTask.dependsOn(nativeTask)
        }
        configureTransformedOutputs(project, javaExtension, mainSourceSet.get(), transformTask, nativeTask)
        artifacts.register(
            targetName = "main",
            protectedJar = project.tasks.named(JavaPlugin.JAR_TASK_NAME, Jar::class.java).flatMap { it.archiveFile },
            requiredShrinkerRules = supportClasses.flatMap { it.outputDirectory.file(STRGUARD_SHRINKER_RULES_FILE_NAME) },
        )
    }

    private fun configureTransformedOutputs(
        project: Project,
        javaExtension: JavaPluginExtension,
        mainSourceSet: SourceSet,
        transformTask: TaskProvider<TransformClassesTask>,
        nativeTask: TaskProvider<BuildNativeRuntimeTask>,
    ) {
        val transformedClasses = project.files(transformTask.flatMap { it.outputDirectory })
        val nativeResources = project.files(nativeTask.flatMap { it.outputDirectory })
        mainSourceSet.runtimeClasspath = transformedClasses + nativeResources + mainSourceSet.runtimeClasspath
        javaExtension.sourceSets.named(SourceSet.TEST_SOURCE_SET_NAME).configure { testSourceSet ->
            testSourceSet.runtimeClasspath = transformedClasses + nativeResources + testSourceSet.runtimeClasspath
        }

        val standardClassOutput = project.layout.buildDirectory.dir("classes").get().asFile.toPath()
        project.tasks.named(JavaPlugin.JAR_TASK_NAME, Jar::class.java).configure { jarTask ->
            jarTask.dependsOn(nativeTask)
            jarTask.isPreserveFileTimestamps = false
            jarTask.isReproducibleFileOrder = true
            jarTask.exclude { element -> element.file.toPath().startsWith(standardClassOutput) }
            jarTask.from(transformedClasses)
            jarTask.from(nativeResources)
        }
    }
}

internal const val CARGO_EXECUTABLE_ENVIRONMENT_VARIABLE = "STRGUARD_CARGO_EXECUTABLE"

internal const val STRGUARD_TASK_GROUP = "strguard"
internal const val DEFAULT_NATIVE_PROCESS_TIMEOUT_SECONDS = 15L * 60L
