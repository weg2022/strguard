package io.github.weg2022.strguard

import io.github.weg2022.strguard.crypto.CryptoPrimitives
import org.gradle.api.*
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile

class StrGuardPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("strGuard", StrGuardExtension::class.java)
        extension.releaseSeedHex.convention(
            project.providers.environmentVariable("STRGUARD_RELEASE_SEED_HEX"),
        )
        val hostTarget =
            project.providers.systemProperty("os.name")
                .zip(project.providers.systemProperty("os.arch")) { osName, architecture ->
                    NativeTarget.detectHost(osName, architecture).rustTriple
                }
        extension.targetTriple.convention(
            project.providers.environmentVariable("STRGUARD_TARGET_TRIPLE").orElse(hostTarget),
        )
        val supportClasses: TaskProvider<PrepareSupportClassesTask> =
            project.tasks.register(
                "prepareStrGuardSupportClasses",
                PrepareSupportClassesTask::class.java,
            )
        supportClasses.configure(Action { task ->
            task.outputDirectory.convention(
                project.layout.buildDirectory.dir("generated/strguard/compile-classpath"),
            )
        })

        project.pluginManager.withPlugin("java") {
            configureJvmProject(project, extension, supportClasses)
        }

        listOf(
            "com.android.application",
            "com.android.library",
            "org.jetbrains.kotlin.multiplatform",
        ).forEach { unsupportedPlugin ->
            project.pluginManager.withPlugin(unsupportedPlugin) {
                throw GradleException(
                    "StrGuard currently supports Java and Kotlin/JVM modules only; " +
                            "use the platform instrumentation API for $unsupportedPlugin.",
                )
            }
        }
    }

    private fun configureJvmProject(
        project: Project,
        extension: StrGuardExtension,
        supportClasses: TaskProvider<PrepareSupportClassesTask>,
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
        transformTask.configure(Action { task ->
            task.inputClassDirectories.from(originalClassDirectories)
            task.outputDirectory.convention(project.layout.buildDirectory.dir("strguard/classes/main"))
            task.nativeInputDirectory.convention(project.layout.buildDirectory.dir("strguard/native-input/main"))
            task.reportDirectory.convention(project.layout.buildDirectory.dir("reports/strguard/main"))
            task.stringGuardEnabled.convention(extension.enabled)
            task.releaseSeedHex.convention(extension.releaseSeedHex)
            task.releaseSeedFingerprint.convention(
                extension.releaseSeedHex
                    .map { seed ->
                        CryptoPrimitives.hex(
                            CryptoPrimitives.sha256(CryptoPrimitives.parseHex256(seed)),
                        )
                    }
                    .orElse("missing"),
            )
            task.moduleIdentity.convention("${project.group}:${project.name}:${project.path}")
            task.targetTriple.convention(extension.targetTriple)
            task.java9StringConcatEnabled.convention(extension.java9StringConcatEnabled)
            task.consoleOutput.convention(extension.consoleOutput)
            task.removeMetadata.convention(extension.removeMetadata)
            task.stringGuardPackages.convention(extension.stringGuardPackages)
            task.keepStringPackages.convention(extension.keepStringPackages)
            task.removeMetadataPackages.convention(extension.removeMetadataPackages)
            task.keepMetadataPackages.convention(extension.keepMetadataPackages)
        })

        val nativeTask: TaskProvider<BuildNativeRuntimeTask> =
            project.tasks.register(
                "buildStrGuardNativeMain",
                BuildNativeRuntimeTask::class.java,
            )
        nativeTask.configure(Action { task ->
            task.dependsOn(transformTask)
            task.nativeInputDirectory.convention(transformTask.flatMap { it.nativeInputDirectory })
            task.outputDirectory.convention(project.layout.buildDirectory.dir("strguard/native-resources/main"))
            task.nativeEnabled.convention(extension.enabled)
            task.targetTriple.convention(extension.targetTriple)
            task.cargoExecutable.convention("cargo")
            task.runtimeTemplateVersion.convention("4")
        })

        project.tasks.named(JavaPlugin.CLASSES_TASK_NAME).configure { classesTask ->
            classesTask.dependsOn(nativeTask)
        }
        configureTransformedOutputs(project, javaExtension, mainSourceSet.get(), transformTask, nativeTask)
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
            jarTask.exclude { element -> element.file.toPath().startsWith(standardClassOutput) }
            jarTask.from(transformedClasses)
            jarTask.from(nativeResources)
        }
    }
}
