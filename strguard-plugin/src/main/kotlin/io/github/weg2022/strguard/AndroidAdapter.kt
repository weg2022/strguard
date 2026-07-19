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
    ) {
        val androidComponents =
            project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
        val ndkVersion = project.objects.property(String::class.java)
        val proguardRules = androidProguardRules(supportClasses)
        addSupportCompileDependency(project, supportClasses)
        androidComponents.finalizeDsl { android ->
            ndkVersion.set(android.ndkVersion)
            if (extension.enabled.get()) {
                validateAbiFilters("defaultConfig", android.defaultConfig.ndk.abiFilters)
                android.productFlavors.forEach { flavor ->
                    validateAbiFilters("product flavor ${flavor.name}", flavor.ndk.abiFilters)
                }
                android.buildTypes.forEach { buildType ->
                    validateAbiFilters("build type ${buildType.name}", buildType.ndk.abiFilters)
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
            )
        }
    }

    fun configureLibrary(
        project: Project,
        extension: StrGuardExtension,
        supportClasses: TaskProvider<PrepareSupportClassesTask>,
    ) {
        val androidComponents =
            project.extensions.getByType(LibraryAndroidComponentsExtension::class.java)
        val ndkVersion = project.objects.property(String::class.java)
        val proguardRules = androidProguardRules(supportClasses)
        addSupportCompileDependency(project, supportClasses)
        androidComponents.finalizeDsl { android ->
            ndkVersion.set(android.ndkVersion)
            android.defaultConfig.consumerProguardFile(proguardRules)
            if (extension.enabled.get()) {
                validateAbiFilters("defaultConfig", android.defaultConfig.ndk.abiFilters)
                android.productFlavors.forEach { flavor ->
                    validateAbiFilters("product flavor ${flavor.name}", flavor.ndk.abiFilters)
                }
                android.buildTypes.forEach { buildType ->
                    validateAbiFilters("build type ${buildType.name}", buildType.ndk.abiFilters)
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
    ) {
        val minSdk = variant.minSdk.apiLevel
        if (extension.enabled.get() && minSdk < ANDROID_ARM64_MIN_SDK) {
            throw GradleException(
                "StrGuard Android arm64-v8a requires minSdk >= $ANDROID_ARM64_MIN_SDK; " +
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
            task.nativeInputDirectory.convention(
                project.layout.buildDirectory.dir("strguard/native-input/${variant.name}"),
            )
            task.reportDirectory.convention(
                project.layout.buildDirectory.dir("reports/strguard/${variant.name}"),
            )
            task.stringGuardEnabled.convention(extension.enabled)
            task.releaseSeedHex.convention(extension.releaseSeedHex)
            task.releaseSeedFingerprint.convention(project.releaseSeedFingerprint(extension))
            task.moduleIdentity.convention("${project.group}:${project.name}:${project.path}:${variant.name}")
            task.targetTriple.convention(NativeTarget.ANDROID_ARM64.rustTriple)
            task.java9StringConcatEnabled.convention(extension.java9StringConcatEnabled)
            task.consoleOutput.convention(extension.consoleOutput)
            task.removeMetadata.convention(extension.removeMetadata)
            task.stringGuardPackages.convention(extension.stringGuardPackages)
            task.keepStringPackages.convention(extension.keepStringPackages)
            task.removeMetadataPackages.convention(extension.removeMetadataPackages)
            task.keepMetadataPackages.convention(extension.keepMetadataPackages)
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
            project.providers.systemProperty("os.name")
                .zip(project.providers.systemProperty("os.arch")) { osName, architecture ->
                    androidNdkHostTag(osName, architecture)
                }
        val nativeTask =
            project.tasks.register(
                "buildStrGuard${variantName}Native",
                BuildAndroidNativeRuntimeTask::class.java,
            )
        nativeTask.configure { task ->
            task.dependsOn(transformTask)
            task.nativeInputDirectory.convention(transformTask.flatMap { it.nativeInputDirectory })
            task.outputDirectory.convention(
                project.layout.buildDirectory.dir("generated/strguard/jniLibs/${variant.name}"),
            )
            task.nativeEnabled.convention(extension.enabled)
            task.targetTriple.convention(NativeTarget.ANDROID_ARM64.rustTriple)
            task.minSdk.convention(minSdk)
            task.ndkHostTag.convention(hostTag)
            task.ndkDirectory.convention(ndkDirectory)
            task.ndkVersion.convention(ndkVersion)
            task.cargoExecutable.convention("cargo")
            task.runtimeTemplateVersion.convention("1")
        }
        val jniLibs = variant.sources.jniLibs
            ?: throw GradleException("Android variant ${variant.name} does not expose jniLibs sources")
        jniLibs.addGeneratedSourceDirectory(nativeTask, BuildAndroidNativeRuntimeTask::outputDirectory)
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
    ): Provider<RegularFile> =
        supportClasses.flatMap { task ->
            task.outputDirectory.file(ANDROID_PROGUARD_RULES_FILE_NAME)
        }
}

private fun validateAbiFilters(owner: String, abiFilters: Set<String>) {
    if (abiFilters.isNotEmpty() && ANDROID_ARM64_ABI !in abiFilters) {
        throw GradleException(
            "StrGuard Android supports $ANDROID_ARM64_ABI only, but $owner configures " +
                    abiFilters.sorted().joinToString(),
        )
    }
}

private const val ANDROID_ARM64_ABI = "arm64-v8a"
private const val ANDROID_ARM64_MIN_SDK = 21
