package io.github.weg2022.strguard

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault
import java.io.IOException
import java.nio.file.*
import java.util.*

@DisableCachingByDefault(because = "Native compiler reproducibility is validated separately per toolchain")
abstract class BuildNativeRuntimeTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val nativeInputDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Input
    abstract val nativeEnabled: Property<Boolean>

    @get:Input
    abstract val targetTriple: Property<String>

    @get:Input
    abstract val cargoExecutable: Property<String>

    @get:Input
    abstract val runtimeTemplateVersion: Property<String>

    @TaskAction
    fun build() {
        val output = outputDirectory.get().asFile.toPath()
        resetDirectory(output)
        if (!nativeEnabled.get()) {
            return
        }

        val nativeTarget = NativeTarget.fromRustTriple(targetTriple.get())
        if (!nativeTarget.extractFromResources) {
            throw GradleException("Android Native targets must be built through an Android variant")
        }
        val target = nativeTarget.rustTriple
        val inputs = nativeInputDirectory.get().asFile.toPath().toAbsolutePath().normalize()
        val workspace = temporaryDir.toPath().resolve("native-runtime")
        val cargoTarget = temporaryDir.toPath().resolve("cargo-target")
        resetDirectory(workspace)
        resetDirectory(cargoTarget)
        copyRuntimeTemplate(workspace)

        val command =
            listOf(
                cargoExecutable.get(),
                "build",
                "--manifest-path",
                workspace.resolve("Cargo.toml").toString(),
                "--release",
                "--locked",
                "--target",
                target,
            )
        val process =
            try {
                ProcessBuilder(command)
                    .directory(workspace.toFile())
                    .redirectErrorStream(true)
                    .apply {
                        environment()["STRGUARD_CONFIG_DIR"] = inputs.toString()
                        environment()["CARGO_TARGET_DIR"] = cargoTarget.toAbsolutePath().toString()
                        environment()["SOURCE_DATE_EPOCH"] = "0"
                    }
                    .start()
            } catch (failure: IOException) {
                throw GradleException(
                    "StrGuard cannot start Cargo executable '${cargoExecutable.get()}' for target $target. " +
                            "Install Rust and Cargo, then add the target with 'rustup target add $target'.",
                    failure,
                )
            }
        val processOutput = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GradleException(
                "StrGuard Rust runtime build failed for target $target " +
                        "(command: ${command.joinToString(" ")}):\n$processOutput\n" +
                        "Ensure Cargo is available and run 'rustup target add $target'.",
            )
        }
        if (processOutput.isNotBlank()) {
            logger.info(processOutput.trim())
        }

        val metadata = Properties()
        Files.newInputStream(inputs.resolve("runtime.properties")).use(metadata::load)
        val resourcePath = metadata.getProperty("resourcePath")
            ?: throw GradleException("StrGuard runtime metadata has no resourcePath")
        val fileName = metadata.getProperty("fileName")
            ?: throw GradleException("StrGuard runtime metadata has no fileName")
        val expectedResourcePath = "META-INF/strguard/native/${nativeTarget.resourceDirectory}/$fileName"
        if (resourcePath != expectedResourcePath) {
            throw GradleException(
                "StrGuard runtime metadata target mismatch: expected $expectedResourcePath, got $resourcePath",
            )
        }
        val compiledLibrary =
            cargoTarget.resolve(target).resolve("release").resolve(nativeTarget.cargoLibraryFileName)
        if (!Files.isRegularFile(compiledLibrary)) {
            throw GradleException("Cargo did not produce $compiledLibrary")
        }
        val packagedLibrary = output.resolve(resourcePath)
        Files.createDirectories(packagedLibrary.parent)
        Files.copy(compiledLibrary, packagedLibrary, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun copyRuntimeTemplate(destination: Path) {
        copyResource("Cargo.toml", destination.resolve("Cargo.toml"))
        copyResource("Cargo.lock", destination.resolve("Cargo.lock"))
        copyResource("src/lib.rs", destination.resolve("src/lib.rs"))
    }

    private fun copyResource(relativePath: String, destination: Path) {
        val resourcePath = "strguard-native-runtime/$relativePath"
        val resource = javaClass.classLoader.getResourceAsStream(resourcePath)
            ?: throw GradleException("Missing bundled Rust runtime source $resourcePath")
        Files.createDirectories(destination.parent)
        resource.use { input ->
            Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun resetDirectory(directory: Path) {
        directory.toFile().deleteRecursively()
        Files.createDirectories(directory)
    }
}
