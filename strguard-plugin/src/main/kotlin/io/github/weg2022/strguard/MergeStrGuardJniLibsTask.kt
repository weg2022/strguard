package io.github.weg2022.strguard

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

@CacheableTask
abstract class MergeStrGuardJniLibsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDirectories: ConfigurableFileCollection

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val nativeInputDirectory: DirectoryProperty

    @get:Input
    abstract val abiNames: ListProperty<String>

    @get:Input
    abstract val nativeEnabled: Property<Boolean>

    @get:Input
    abstract val minSdk: Property<Int>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val metadataOutputDirectory: DirectoryProperty

    @TaskAction
    fun merge() {
        val output = outputDirectory.get().asFile.toPath()
        val metadataOutput = metadataOutputDirectory.get().asFile.toPath()
        resetDirectory(output)
        resetDirectory(metadataOutput)
        if (!nativeEnabled.get()) {
            return
        }

        val rootsByAbi =
            inputDirectories.files.associate { directory ->
                val root = directory.toPath().toAbsolutePath().normalize()
                val metadata = Properties()
                val metadataFile = root.resolve(ANDROID_NATIVE_OUTPUT_METADATA_FILE_NAME)
                if (!Files.isRegularFile(metadataFile)) {
                    throw GradleException("StrGuard Android Native output has no ABI metadata")
                }
                Files.newInputStream(metadataFile).use(metadata::load)
                if (metadata.getProperty("schemaVersion") != "1") {
                    throw GradleException("StrGuard Android Native output has an unsupported metadata schema")
                }
                val abiName = metadata.getProperty("abiName")
                    ?: throw GradleException("StrGuard Android Native output has no abiName")
                AndroidAbi.fromAbiName(abiName)
                abiName to root
            }
        if (rootsByAbi.size != inputDirectories.files.size) {
            throw GradleException("StrGuard Android Native outputs contain duplicate ABI metadata")
        }
        val nativeResources = linkedMapOf<String, String>()
        abiNames.get().map(AndroidAbi::fromAbiName).forEach { abi ->
            val root = rootsByAbi[abi.abiName]
                ?: throw GradleException("StrGuard has no Native output for ${abi.abiName}")
            val libraries =
                Files.walk(root).use { paths ->
                    paths.iterator().asSequence().filter { library ->
                        val name = library.fileName.toString()
                        Files.isRegularFile(library) &&
                            name.startsWith("libsg_") &&
                            name.endsWith(abi.libraryExtension)
                    }.toList()
                }
            if (libraries.size != 1) {
                throw GradleException(
                    "StrGuard expected exactly one ${abi.abiName} Native library, found ${libraries.size}",
                )
            }
            val destination = output.resolve(abi.packagingDirectory).resolve(libraries.single().fileName)
            Files.createDirectories(destination.parent)
            Files.copy(libraries.single(), destination, StandardCopyOption.REPLACE_EXISTING)
            val resourcePath = abi.packagedResourcePath(destination.fileName.toString())
            nativeResources[resourcePath] = sha256(destination)
        }
        val runtimeProperties = Properties()
        Files.newInputStream(nativeInputDirectory.file("runtime.properties").get().asFile.toPath())
            .use(runtimeProperties::load)
        if (runtimeProperties.getProperty("runtimeFamily") != "android") {
            throw GradleException("StrGuard runtime metadata is not an Android runtime")
        }
        writeArtifactMetadata(
            metadataOutput,
            StrGuardArtifactMetadata.fromRuntimeProperties(
                properties = runtimeProperties,
                runtimeTarget = "android",
                nativeResources = nativeResources,
                androidAbis = abiNames.get(),
                minSdk = minSdk.get(),
            ),
        )
    }
}
