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
            } else if (target.name != "metadata") {
                // metadata 是 KMP 自动创建、用户无法移除的内部占位 target,每次构建都会走到这里;
                // 静默它,避免误导性的 pass-through 噪音。js/native 等用户显式声明的 target
                // 保留 lifecycle 提示仍有价值。
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
            // 帧合并解析类型需要依赖 jar 中的类(如 Compose 的 PopupPositionProvider)
            task.resolutionClasspath.from(compilation.compileDependencyFiles)
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
            task.removeSourceDebugExtension.convention(extension.removeSourceDebugExtension)
            task.stringGuardPackages.convention(
                project.strGuardPackageSelectors(extension, extension.stringGuardPackages, "stringGuardPackages"),
            )
            task.keepStringPackages.convention(
                project.strGuardPackageSelectors(extension, extension.keepStringPackages, "keepStringPackages"),
            )
            task.removeSourceDebugExtensionPackages.convention(
                project.strGuardPackageSelectors(extension, extension.removeSourceDebugExtensionPackages, "removeSourceDebugExtensionPackages"),
            )
            task.keepSourceDebugExtensionPackages.convention(
                project.strGuardPackageSelectors(extension, extension.keepSourceDebugExtensionPackages, "keepSourceDebugExtensionPackages"),
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
        // 配置缓存安全性:exclude 闭包会连同捕获变量一起被序列化进 Jar 任务的 mainSpec。
        // 直接捕获 KGP 的 compilation bean,引用链会拖入 KotlinJvmTarget_Decorated
        // (binariesDsl$delegate 是 SynchronizedLazyImpl)、DefaultSourceSet、JavaCompile,
        // 导致配置缓存序列化失败。先包装成 Gradle 自有的 ConfigurableFileCollection,
        // 配置缓存对它按文件路径列表存储,闭包只捕获这个包装集合。
        // 注意:不能用 project.files { } 工厂闭包形式——callable 本身会重新捕获 compilation。
        val originalClassDirs = project.files(compilation.output.classesDirs)
        project.tasks.named(target.artifactsTaskName, Jar::class.java).configure { jarTask ->
            jarTask.dependsOn(nativeTask)
            jarTask.isPreserveFileTimestamps = false
            jarTask.isReproducibleFileOrder = true
            jarTask.exclude { element ->
                val source = element.file.toPath().toAbsolutePath().normalize()
                originalClassDirs.files.any { classDirectory ->
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
