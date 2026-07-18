package io.github.weg2022.strguard

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

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

        val target = targetTriple.get()
        if (target != WINDOWS_X64_TARGET) {
            throw GradleException("StrGuard 2 currently validates only $WINDOWS_X64_TARGET, got $target")
        }
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
            ProcessBuilder(command)
                .directory(workspace.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment()["STRGUARD_CONFIG_DIR"] = inputs.toString()
                    environment()["CARGO_TARGET_DIR"] = cargoTarget.toAbsolutePath().toString()
                    environment()["SOURCE_DATE_EPOCH"] = "0"
                }
                .start()
        val processOutput = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GradleException("StrGuard Rust runtime build failed:\n$processOutput")
        }
        if (processOutput.isNotBlank()) {
            logger.info(processOutput.trim())
        }

        val metadata = Properties()
        Files.newInputStream(inputs.resolve("runtime.properties")).use(metadata::load)
        val resourcePath = metadata.getProperty("resourcePath")
            ?: throw GradleException("StrGuard runtime metadata has no resourcePath")
        val compiledLibrary = cargoTarget.resolve(target).resolve("release").resolve("strguard_native.dll")
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

private const val WINDOWS_X64_TARGET = "x86_64-pc-windows-msvc"
