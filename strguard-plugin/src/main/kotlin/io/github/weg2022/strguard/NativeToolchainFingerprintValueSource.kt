package io.github.weg2022.strguard

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

abstract class NativeToolchainFingerprintValueSource : ValueSource<String, NativeToolchainFingerprintValueSource.Parameters> {
    interface Parameters : ValueSourceParameters {
        val cargoExecutable: Property<String>
        val targetTriple: Property<String>
        val linkerExecutable: RegularFileProperty
        val archiverExecutable: RegularFileProperty
        val workingDirectory: DirectoryProperty
        val configurationFiles: ConfigurableFileCollection
    }

    override fun obtain(): String {
        val cargo = parameters.cargoExecutable.get()
        val target = parameters.targetTriple.get()
        val rustc =
            runCatching {
                val cargoPath = java.nio.file.Path.of(cargo)
                if (cargoPath.parent == null) "rustc" else cargoPath.parent.resolve(rustcExecutableName()).toString()
            }.getOrDefault("rustc")
        val material =
            buildString {
                appendLine(target)
                appendLine(System.getProperty("os.name"))
                appendLine(System.getProperty("os.version"))
                appendLine(System.getProperty("os.arch"))
                parameters.configurationFiles.files
                    .map { file -> file.toPath().toAbsolutePath().normalize() }
                    .filter(Files::isRegularFile)
                    .distinct()
                    .sorted()
                    .forEach { config ->
                        appendLine(config.fileName)
                        appendLine(Files.size(config))
                        appendLine(sha256(config))
                    }
                append(runVersion("Cargo", cargo, listOf("--version", "--verbose"), target))
                append(runVersion("rustc", rustc, listOf("-Vv"), target))
                val targetMsvc =
                    if (parameters.linkerExecutable.isPresent) {
                        null
                    } else {
                        runCatching {
                            findMsvcToolchain(JvmNativeTarget.fromRustTriple(parameters.targetTriple.get()))
                        }.getOrNull()
                    }
                val hostMsvc =
                    runCatching {
                        findMsvcToolchain(
                            JvmNativeTarget.detectHost(
                                System.getProperty("os.name"),
                                System.getProperty("os.arch"),
                            ),
                        )
                    }.getOrNull()
                val linker =
                    if (parameters.linkerExecutable.isPresent) {
                        parameters.linkerExecutable.get().asFile.toPath()
                    } else {
                        targetMsvc?.linker
                    }
                listOfNotNull(
                    linker,
                    parameters.archiverExecutable.orNull?.asFile?.toPath(),
                ).forEach { executable ->
                    appendLine(executable.fileName)
                    appendLine(Files.size(executable))
                    appendLine(sha256(executable))
                }
                listOfNotNull(targetMsvc, hostMsvc)
                    .distinctBy { toolchain -> toolchain.linker.toAbsolutePath().normalize() }
                    .forEach { toolchain ->
                        appendLine(toolchain.linker.fileName)
                        appendLine(Files.size(toolchain.linker))
                        appendLine(sha256(toolchain.linker))
                        appendMsvcLibraryIdentity(toolchain)
                    }
                if (hostMsvc == null && !System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                    append(runOptionalVersion("cc", listOf("--version")))
                    val linkerArguments =
                        if (System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) listOf("-v") else listOf("--version")
                    append(runOptionalVersion("ld", linkerArguments))
                }
            }
        return MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun runVersion(
        toolName: String,
        executable: String,
        arguments: List<String>,
        target: String,
    ): String {
        val process =
            try {
                ProcessBuilder(listOf(executable) + arguments)
                    .directory(probeWorkingDirectory())
                    .redirectErrorStream(true)
                    .apply {
                        environment().clear()
                        environment().putAll(nativeProcessEnvironment(emptyMap()))
                    }.start()
            } catch (failure: Exception) {
                throw GradleException(
                    "StrGuard cannot start $toolName executable '$executable' while fingerprinting target $target. " +
                        "Ensure Rust/Cargo is installed and run 'rustup target add $target'.",
                    failure,
                )
            }
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            terminateProcessTree(process.toHandle())
            throw GradleException("Timed out while fingerprinting $toolName executable '$executable' for target $target")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (process.exitValue() != 0) {
            throw GradleException(
                "$toolName executable '$executable' version probe failed for target $target: ${output.take(4096)}",
            )
        }
        return output
    }

    private fun rustcExecutableName(): String = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "rustc.exe" else "rustc"

    private fun runOptionalVersion(executable: String, arguments: List<String>): String = runCatching {
        val process =
            ProcessBuilder(listOf(executable) + arguments)
                .directory(probeWorkingDirectory())
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment().putAll(nativeProcessEnvironment(emptyMap()))
                }.start()
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            terminateProcessTree(process.toHandle())
            return@runCatching "$executable=timeout\n"
        }
        "$executable=${process.exitValue()}\n${process.inputStream.bufferedReader().use { it.readText() }}"
    }.getOrElse { "$executable=unavailable\n" }

    private fun probeWorkingDirectory(): java.io.File {
        val directory = parameters.workingDirectory.get().asFile
        try {
            Files.createDirectories(directory.toPath())
        } catch (failure: Exception) {
            throw GradleException("StrGuard cannot create the Native toolchain probe directory $directory", failure)
        }
        return directory
    }

    private fun sha256(file: java.nio.file.Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun StringBuilder.appendMsvcLibraryIdentity(toolchain: MsvcToolchain) {
        toolchain.libraryPath.split(java.io.File.pathSeparatorChar).forEach { entry ->
            val directory = java.nio.file.Path.of(entry)
            appendLine(
                directory.iterator().asSequence().map { component -> component.toString() }
                    .toList().takeLast(4).joinToString("/"),
            )
            MSVC_LIBRARY_FINGERPRINT_FILES.forEach { fileName ->
                val library = directory.resolve(fileName)
                if (Files.isRegularFile(library)) {
                    appendLine(fileName)
                    appendLine(Files.size(library))
                    appendLine(sha256(library))
                }
            }
        }
    }
}

internal fun NativeToolchainFingerprintValueSource.Parameters.captureBuildEnvironment(
    project: Project,
    task: Task,
) {
    workingDirectory.set(task.temporaryDir)
    configurationFiles.from(
        project.nativeCargoConfigurationFiles(task),
        project.nativeRustToolchainFiles(task),
    )
}

internal fun Project.nativeCargoConfigurationFiles(task: Task): ConfigurableFileCollection = nativeTaskConfigurationFiles(task, ".cargo/config", ".cargo/config.toml")

private fun Project.nativeRustToolchainFiles(task: Task): ConfigurableFileCollection = nativeTaskConfigurationFiles(task, "rust-toolchain", "rust-toolchain.toml")

private fun Project.nativeTaskConfigurationFiles(
    task: Task,
    vararg relativePaths: String,
): ConfigurableFileCollection = objects.fileCollection().from(
    ancestorBuildFiles(projectDir.toPath(), *relativePaths),
    ancestorBuildFiles(task.temporaryDir.toPath(), *relativePaths),
)

private fun ancestorBuildFiles(
    start: java.nio.file.Path,
    vararg relativePaths: String,
): Set<java.io.File> {
    val candidates = linkedSetOf<java.io.File>()
    var directory: java.nio.file.Path? = start.toAbsolutePath().normalize()
    while (directory != null) {
        relativePaths.forEach { relativePath -> candidates += directory.resolve(relativePath).toFile() }
        directory = directory.parent
    }
    return candidates
}

private val MSVC_LIBRARY_FINGERPRINT_FILES =
    listOf("libcmt.lib", "msvcrt.lib", "vcruntime.lib", "ucrt.lib", "kernel32.lib", "advapi32.lib", "userenv.lib", "ws2_32.lib")
