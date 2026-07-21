package io.github.weg2022.strguard

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.*
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import java.util.*

internal object AndroidAdapter {
    fun configureApplication(
        project: Project,
        extension: StrGuardExtension,
        supportClasses: TaskProvider<PrepareSupportClassesTask>,
        processRegistry: Provider<NativeProcessRegistryService>,
    ) {
        val androidComponents =
            project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
        val ndkVersion = project.objects.property(String::class.java)
        val proguardRules = androidProguardRules(supportClasses)
        addSupportCompileDependency(project, supportClasses)
        androidComponents.finalizeDsl { android ->
            if (extension.enabled.get()) {
                ndkVersion.set(android.ndkVersion)
                val configuredAbis = configuredAndroidAbis(extension)
                validateAbiFilters("defaultConfig", android.defaultConfig.ndk.abiFilters, configuredAbis)
                android.productFlavors.forEach { flavor ->
                    validateAbiFilters("product flavor ${flavor.name}", flavor.ndk.abiFilters, configuredAbis)
                }
                android.buildTypes.forEach { buildType ->
                    validateAbiFilters("build type ${buildType.name}", buildType.ndk.abiFilters, configuredAbis)
                }
            }
        }
        androidComponents.onVariants { variant ->
            if (extension.enabled.get()) {
                validateAbiSplitOutputs(variant, configuredAndroidAbis(extension))
            }
            configureVariant(
                project,
                extension,
                variant,
                androidComponents.sdkComponents.ndkDirectory,
                ndkVersion,
                proguardRules,
                processRegistry,
            )
        }
    }

    fun configureLibrary(
        project: Project,
        extension: StrGuardExtension,
        supportClasses: TaskProvider<PrepareSupportClassesTask>,
        processRegistry: Provider<NativeProcessRegistryService>,
    ) {
        val androidComponents =
            project.extensions.getByType(LibraryAndroidComponentsExtension::class.java)
        val ndkVersion = project.objects.property(String::class.java)
        val proguardRules = androidProguardRules(supportClasses)
        addSupportCompileDependency(project, supportClasses)
        androidComponents.finalizeDsl { android ->
            android.defaultConfig.consumerProguardFile(proguardRules)
            if (extension.enabled.get()) {
                ndkVersion.set(android.ndkVersion)
                val configuredAbis = configuredAndroidAbis(extension)
                validateAbiFilters("defaultConfig", android.defaultConfig.ndk.abiFilters, configuredAbis)
                android.productFlavors.forEach { flavor ->
                    validateAbiFilters("product flavor ${flavor.name}", flavor.ndk.abiFilters, configuredAbis)
                }
                android.buildTypes.forEach { buildType ->
                    validateAbiFilters("build type ${buildType.name}", buildType.ndk.abiFilters, configuredAbis)
                }
            }
        }
        androidComponents.onVariants { variant ->
            configureVariant(
                project,
                extension,
                variant,
                androidComponents.sdkComponents.ndkDirectory,
                ndkVersion,
                proguardRules,
                processRegistry,
            )
        }
    }

    private fun configureVariant(
        project: Project,
        extension: StrGuardExtension,
        variant: Variant,
        ndkDirectory: Provider<Directory>,
        ndkVersion: Provider<String>,
        proguardRules: Provider<RegularFile>,
        processRegistry: Provider<NativeProcessRegistryService>,
    ) {
        val minSdk = variant.minSdk.apiLevel
        if (extension.enabled.get() && minSdk < ANDROID_MIN_SDK) {
            throw GradleException(
                "StrGuard Android Native runtime requires minSdk >= $ANDROID_MIN_SDK; " +
                    "variant ${variant.name} uses minSdk $minSdk",
            )
        }
        val variantName = variant.name.replaceFirstChar { character ->
            if (character.isLowerCase()) character.titlecase(Locale.ROOT) else character.toString()
        }
        val transformTask =
            project.tasks.register(
                "transformStrGuard${variantName}Classes",
                TransformAndroidClassesTask::class.java,
            )
        transformTask.configure { task ->
            task.group = STRGUARD_TASK_GROUP
            task.description = "Transforms the ${variant.name} Android variant classes with StrGuard."
            task.nativeInputDirectory.convention(
                project.layout.buildDirectory.dir("strguard/native-input/${variant.name}"),
            )
            task.reportDirectory.convention(
                project.layout.buildDirectory.dir("reports/strguard/${variant.name}"),
            )
            task.stringGuardEnabled.convention(extension.enabled)
            task.releaseSeedHex.convention(project.releaseSeed(extension))
            task.releaseSeedFingerprint.convention(project.releaseSeedFingerprint(extension))
            task.moduleIdentity.convention("${project.group}:${project.name}:${project.path}:${variant.name}")
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
        }
        variant.artifacts
            .forScope(ScopedArtifacts.Scope.PROJECT)
            .use(transformTask)
            .toTransform(
                ScopedArtifact.CLASSES,
                TransformAndroidClassesTask::inputJars,
                TransformAndroidClassesTask::inputDirectories,
                TransformAndroidClassesTask::outputJar,
            )
        variant.proguardFiles.add(proguardRules)

        val hostTag =
            project.strGuardProvider(
                enabled = extension.enabled,
                enabledValue =
                project.providers.systemProperty("os.name")
                    .zip(project.providers.systemProperty("os.arch")) { osName, architecture ->
                        androidNdkHostTag(osName, architecture)
                    },
                disabledValue = DISABLED_STRGUARD_VALUE,
            )
        val configuredAbis =
            if (extension.enabled.get()) {
                configuredAndroidAbis(extension)
            } else {
                listOf(AndroidAbi.ARM64_V8A)
            }
        val nativeTasks =
            configuredAbis.map { abi ->
                project.tasks.register(
                    "buildStrGuard${variantName}${abi.taskSuffix}Native",
                    BuildAndroidNativeRuntimeTask::class.java,
                ).also { nativeTask ->
                    nativeTask.configure { task ->
                        task.group = STRGUARD_TASK_GROUP
                        task.description =
                            "Builds the StrGuard ${abi.abiName} Native runtime for the ${variant.name} Android variant."
                        task.dependsOn(transformTask)
                        task.nativeInputDirectory.convention(transformTask.flatMap { it.nativeInputDirectory })
                        task.outputDirectory.convention(
                            project.layout.buildDirectory.dir(
                                "strguard/android-native/${variant.name}/${abi.abiName}",
                            ),
                        )
                        task.nativeEnabled.convention(extension.enabled)
                        task.abiName.convention(abi.abiName)
                        task.minSdk.convention(minSdk)
                        task.ndkHostTag.convention(hostTag)
                        task.ndkDirectory.convention(
                            strGuardProvider(
                                enabled = extension.enabled,
                                enabledValue = ndkDirectory,
                                disabledValue = project.layout.buildDirectory.dir("strguard/disabled-ndk"),
                            ),
                        )
                        task.ndkVersion.convention(
                            project.strGuardProvider(
                                enabled = extension.enabled,
                                enabledValue = ndkVersion,
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
                        task.runtimeTemplateVersion.convention("2")
                        task.processTimeoutSeconds.convention(DEFAULT_NATIVE_PROCESS_TIMEOUT_SECONDS)
                        val linkerFile =
                            project.layout.file(
                                ndkDirectory.zip(hostTag) { directory, tag ->
                                    val host = AndroidNdkHost.fromTag(tag)
                                    directory.asFile.resolve(
                                        "toolchains/llvm/prebuilt/$tag/bin/${host.clangExecutableName(abi, minSdk)}",
                                    )
                                },
                            )
                        task.toolchainFingerprint.convention(
                            project.strGuardProvider(
                                enabled = extension.enabled,
                                enabledValue =
                                project.providers.of(NativeToolchainFingerprintValueSource::class.java) { spec ->
                                    spec.parameters.cargoExecutable.set(task.cargoExecutable)
                                    spec.parameters.targetTriple.set(abi.rustTriple)
                                    spec.parameters.linkerExecutable.set(linkerFile)
                                },
                                disabledValue = DISABLED_STRGUARD_VALUE,
                            ),
                        )
                        task.processRegistry.set(processRegistry)
                        task.usesService(processRegistry)
                    }
                }
            }
        val nativeTask =
            project.tasks.register(
                "buildStrGuard${variantName}Native",
                MergeStrGuardJniLibsTask::class.java,
            )
        nativeTask.configure { task ->
            task.group = STRGUARD_TASK_GROUP
            task.description = "Merges StrGuard Native runtimes for the ${variant.name} Android variant."
            task.dependsOn(nativeTasks)
            task.inputDirectories.from(nativeTasks.map { it.flatMap(BuildAndroidNativeRuntimeTask::outputDirectory) })
            task.nativeInputDirectory.convention(transformTask.flatMap { it.nativeInputDirectory })
            task.abiNames.convention(configuredAbis.map(AndroidAbi::abiName))
            task.nativeEnabled.convention(extension.enabled)
            task.minSdk.convention(minSdk)
            task.outputDirectory.convention(
                project.layout.buildDirectory.dir("generated/strguard/jniLibs/${variant.name}"),
            )
            task.metadataOutputDirectory.convention(
                project.layout.buildDirectory.dir("generated/strguard/resources/${variant.name}"),
            )
        }
        val jniLibs = variant.sources.jniLibs
            ?: throw GradleException("Android variant ${variant.name} does not expose jniLibs sources")
        jniLibs.addGeneratedSourceDirectory(nativeTask, MergeStrGuardJniLibsTask::outputDirectory)
        val resources = variant.sources.resources
            ?: throw GradleException("Android variant ${variant.name} does not expose Java resources sources")
        resources.addGeneratedSourceDirectory(nativeTask, MergeStrGuardJniLibsTask::metadataOutputDirectory)
    }

    private fun addSupportCompileDependency(
        project: Project,
        supportClasses: TaskProvider<PrepareSupportClassesTask>,
    ) {
        val supportClasspath = project.files(supportClasses.flatMap { it.outputDirectory })
        project.dependencies.add("compileOnly", supportClasspath)
    }

    private fun androidProguardRules(
        supportClasses: TaskProvider<PrepareSupportClassesTask>,
    ): Provider<RegularFile> = supportClasses.flatMap { task ->
        task.outputDirectory.file(STRGUARD_SHRINKER_RULES_FILE_NAME)
    }
}

private fun configuredAndroidAbis(extension: StrGuardExtension): List<AndroidAbi> {
    val configured = extension.androidAbis.get().map(AndroidAbi::fromAbiName).distinct()
    if (configured.isEmpty()) {
        throw GradleException("StrGuard androidAbis must contain at least one supported Android ABI")
    }
    return configured.sortedBy(AndroidAbi::abiName)
}

private fun validateAbiFilters(
    owner: String,
    abiFilters: Set<String>,
    configuredAbis: List<AndroidAbi>,
) {
    val configuredNames = configuredAbis.map(AndroidAbi::abiName).toSet()
    val unsupported = abiFilters - configuredNames
    if (unsupported.isNotEmpty()) {
        throw GradleException(
            "StrGuard $owner ABI filters ${abiFilters.sorted().joinToString()} require missing " +
                "androidAbis ${unsupported.sorted().joinToString()}",
        )
    }
}

private fun validateAbiSplitOutputs(
    variant: ApplicationVariant,
    configuredAbis: List<AndroidAbi>,
) {
    val configuredNames = configuredAbis.map(AndroidAbi::abiName).toSet()
    val splitAbis =
        variant.outputs
            .flatMap(VariantOutput::filters)
            .filter { filter -> filter.filterType == FilterConfiguration.FilterType.ABI }
            .map(FilterConfiguration::identifier)
            .toSet()
    val unsupported = splitAbis - configuredNames
    if (unsupported.isNotEmpty()) {
        throw GradleException(
            "StrGuard variant ${variant.name} ABI split outputs ${unsupported.sorted().joinToString()} " +
                "require missing androidAbis ${unsupported.sorted().joinToString()}",
        )
    }
}

private const val ANDROID_MIN_SDK = 21
