package io.github.weg2022.strguard

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import java.nio.file.*
import java.time.Duration
import java.util.*

@CacheableTask
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

    @get:Input
    abstract val processTimeoutSeconds: Property<Long>

    @get:Input
    abstract val toolchainFingerprint: Property<String>

    @get:Input
    abstract val externalCargoConfigurationPresent: Property<Boolean>

    @get:Internal
    abstract val processRegistry: Property<NativeProcessRegistryService>

    init {
        outputs.doNotCacheIf("External Cargo configuration may select untracked build tools") {
            externalCargoConfigurationPresent.get()
        }
        outputs.upToDateWhen { !externalCargoConfigurationPresent.get() }
    }

    @TaskAction
    fun build() {
        val output = outputDirectory.get().asFile.toPath()
        resetDirectory(output)
        if (!nativeEnabled.get()) {
            return
        }

        val nativeTarget = JvmNativeTarget.fromRustTriple(targetTriple.get())
        val target = nativeTarget.rustTriple
        val inputs = nativeInputDirectory.get().asFile.toPath().toAbsolutePath().normalize()
        val workspace = temporaryDir.toPath().resolve("native-runtime")
        val cargoTarget = temporaryDir.toPath().resolve("cargo-target")
        val cargoHome = temporaryDir.toPath().resolve("cargo-home")
        val privateHome = cargoHome.resolve("home")
        resetDirectory(workspace)
        resetDirectory(cargoTarget)
        resetDirectory(cargoHome)
        Files.createDirectories(privateHome)
        copyRuntimeTemplate(workspace)
        val environment =
            linkedMapOf(
                "CARGO_TARGET_DIR" to cargoTarget.toAbsolutePath().toString(),
                "CARGO_HOME" to cargoHome.toAbsolutePath().toString(),
                "HOME" to privateHome.toAbsolutePath().toString(),
                "USERPROFILE" to privateHome.toAbsolutePath().toString(),
                "RUSTUP_HOME" to rustupHomeDirectory(),
                "CARGO_INCREMENTAL" to "0",
                "CARGO_ENCODED_RUSTFLAGS" to encodedReproducibleRustFlags(temporaryDir.toPath(), target),
                "SOURCE_DATE_EPOCH" to "0",
            )
        findMsvcToolchain(nativeTarget)?.let { toolchain ->
            environment[cargoLinkerEnvironmentKey(nativeTarget)] = toolchain.linker.toString()
            environment["LIB"] = toolchain.libraryPath
        }
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
                "StrGuard Rust runtime build failed for target $target. " +
                    "Ensure Cargo is available and run 'rustup target add $target'.",
                failure,
            )
        }

        val metadata = Properties()
        Files.newInputStream(inputs.resolve("runtime.properties")).use(metadata::load)
        val resourcePath = metadata.getProperty("resourcePath")
            ?: throw GradleException("StrGuard runtime metadata has no resourcePath")
        val fileName = metadata.getProperty("fileName")
            ?: throw GradleException("StrGuard runtime metadata has no fileName")
        if (metadata.getProperty("runtimeFamily") != "jvm") {
            throw GradleException("StrGuard runtime metadata is not a JVM runtime")
        }
        val expectedResourcePath = nativeTarget.packagedResourcePath(fileName)
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
        writeArtifactMetadata(
            output,
            StrGuardArtifactMetadata.fromRuntimeProperties(
                properties = metadata,
                runtimeTarget = nativeTarget.rustTriple,
                nativeResources = mapOf(resourcePath to sha256(packagedLibrary)),
            ),
        )
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
