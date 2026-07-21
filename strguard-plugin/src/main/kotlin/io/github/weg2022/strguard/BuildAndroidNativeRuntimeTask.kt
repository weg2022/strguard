package io.github.weg2022.strguard

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault
import java.nio.file.*
import java.time.Duration
import java.util.*

@DisableCachingByDefault(because = "Native compiler reproducibility is validated separately per NDK toolchain")
abstract class BuildAndroidNativeRuntimeTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val nativeInputDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Input
    abstract val nativeEnabled: Property<Boolean>

    @get:Input
    abstract val abiName: Property<String>

    @get:Input
    abstract val minSdk: Property<Int>

    @get:Input
    abstract val ndkHostTag: Property<String>

    @get:Internal
    abstract val ndkDirectory: DirectoryProperty

    @get:Input
    abstract val ndkVersion: Property<String>

    @get:Input
    abstract val cargoExecutable: Property<String>

    @get:Input
    abstract val runtimeTemplateVersion: Property<String>

    @get:Input
    abstract val processTimeoutSeconds: Property<Long>

    @get:Input
    abstract val toolchainFingerprint: Property<String>

    @get:Internal
    abstract val processRegistry: Property<NativeProcessRegistryService>

    init {
        outputs.doNotCacheIf("StrGuard Android Native outputs contain build-specific key material") { true }
    }

    @TaskAction
    fun build() {
        val output = outputDirectory.get().asFile.toPath()
        resetDirectory(output)
        if (!nativeEnabled.get()) {
            return
        }

        val abi = AndroidAbi.fromAbiName(abiName.get())
        val inputs = nativeInputDirectory.get().asFile.toPath().toAbsolutePath().normalize()
        val workspace = temporaryDir.toPath().resolve("native-runtime")
        val cargoTarget = temporaryDir.toPath().resolve("cargo-target")
        resetDirectory(workspace)
        resetDirectory(cargoTarget)
        copyRuntimeTemplate(workspace)

        val toolchain =
            ndkDirectory.get().asFile.toPath()
                .resolve("toolchains/llvm/prebuilt")
                .resolve(ndkHostTag.get())
                .resolve("bin")
        val ndkHost = AndroidNdkHost.fromTag(ndkHostTag.get())
        val linker = toolchain.resolve(ndkHost.clangExecutableName(abi, minSdk.get()))
        val archiver = toolchain.resolve(ndkHost.executableName("llvm-ar"))
        if (!Files.isRegularFile(linker)) {
            throw GradleException("StrGuard cannot find Android NDK linker $linker")
        }
        if (!Files.isRegularFile(archiver)) {
            throw GradleException("StrGuard cannot find Android NDK archiver $archiver")
        }

        val target = abi.rustTriple
        val environment =
            linkedMapOf(
                "CARGO_TARGET_DIR" to cargoTarget.toAbsolutePath().toString(),
                "CARGO_INCREMENTAL" to "0",
                "SOURCE_DATE_EPOCH" to "0",
                abi.cargoLinkerEnvKey to linker.toString(),
                abi.cargoArchiverEnvKey to archiver.toString(),
                "CC_${abi.cargoToolEnvironmentSuffix}" to linker.toString(),
                "AR_${abi.cargoToolEnvironmentSuffix}" to archiver.toString(),
            )
        addHostMsvcEnvironment(environment)
        val manifest = workspace.resolve("Cargo.toml").toString()
        try {
            val runner = NativeProcessRunner(processRegistry.get())
            runner.run(
                command =
                listOf(
                    cargoExecutable.get(),
                    "fetch",
                    "--manifest-path",
                    manifest,
                    "--locked",
                    "--offline",
                    "--target",
                    target,
                ),
                workingDirectory = workspace,
                environment = nativeProcessEnvironment(environment),
                timeout = Duration.ofSeconds(processTimeoutSeconds.get()),
            )
            copyNativeInputs(inputs, workspace.resolve("generated"))
            runner.run(
                command =
                listOf(
                    cargoExecutable.get(),
                    "build",
                    "--manifest-path",
                    manifest,
                    "--release",
                    "--locked",
                    "--offline",
                    "--target",
                    target,
                ),
                workingDirectory = workspace,
                environment = nativeProcessEnvironment(environment),
                timeout = Duration.ofSeconds(processTimeoutSeconds.get()),
            )
        } catch (failure: GradleException) {
            throw GradleException(
                "StrGuard Android Rust runtime build failed for target $target with NDK ${ndkVersion.get()}. " +
                    "Ensure Cargo/NDK are available and run 'rustup target add $target'.",
                failure,
            )
        }

        val metadata = Properties()
        Files.newInputStream(inputs.resolve("runtime.properties")).use(metadata::load)
        val resourcePath = metadata.getProperty("resourcePath")
            ?: throw GradleException("StrGuard runtime metadata has no resourcePath")
        val fileName = metadata.getProperty("fileName")
            ?: throw GradleException("StrGuard runtime metadata has no fileName")
        if (metadata.getProperty("runtimeFamily") != "android") {
            throw GradleException("StrGuard runtime metadata is not an Android runtime")
        }
        if (resourcePath != fileName) {
            throw GradleException(
                "StrGuard Android runtime metadata path mismatch: expected $fileName, got $resourcePath",
            )
        }
        val expectedResourcePath = abi.packagedResourcePath(fileName)
        val compiledLibrary =
            cargoTarget.resolve(target).resolve("release").resolve(abi.cargoLibraryFileName)
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
        copyResource("build.rs", destination.resolve("build.rs"))
        copyResource("src/lib.rs", destination.resolve("src/lib.rs"))
        extractResourceArchive("vendor.zip", destination)
    }

    private fun copyNativeInputs(source: Path, destination: Path) {
        Files.createDirectories(destination)
        listOf("native_config.rs", "vault.bin").forEach { fileName ->
            Files.copy(source.resolve(fileName), destination.resolve(fileName), StandardCopyOption.REPLACE_EXISTING)
        }
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

    private fun extractResourceArchive(relativePath: String, destination: Path) {
        val resourcePath = "strguard-native-runtime/$relativePath"
        val resource = javaClass.classLoader.getResourceAsStream(resourcePath)
            ?: throw GradleException("Missing bundled Rust runtime archive $resourcePath")
        resource.use { input -> extractZip(input, destination) }
    }
}

internal fun androidNdkHostTag(osName: String, architecture: String): String = AndroidNdkHost.detect(osName, architecture).tag
