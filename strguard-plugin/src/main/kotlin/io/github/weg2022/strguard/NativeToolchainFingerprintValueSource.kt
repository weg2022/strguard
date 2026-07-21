package io.github.weg2022.strguard

import org.gradle.api.GradleException
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
    }

    override fun obtain(): String {
        val cargo = parameters.cargoExecutable.get()
        val target = parameters.targetTriple.get()
        val rustc =
            runCatching {
                val cargoPath = java.nio.file.Path.of(cargo)
                if (cargoPath.parent == null) "rustc" else cargoPath.parent.resolve(executableName("rustc")).toString()
            }.getOrDefault("rustc")
        val material =
            buildString {
                appendLine(target)
                append(runVersion("Cargo", cargo, listOf("--version", "--verbose"), target))
                append(runVersion("rustc", rustc, listOf("-Vv"), target))
                val linker =
                    if (parameters.linkerExecutable.isPresent) {
                        parameters.linkerExecutable.get().asFile.toPath()
                    } else {
                        runCatching {
                            findMsvcToolchain(JvmNativeTarget.fromRustTriple(parameters.targetTriple.get()))?.linker
                        }.getOrNull()
                    }
                if (linker != null) {
                    appendLine(linker.toAbsolutePath().normalize())
                    appendLine(Files.size(linker))
                    appendLine(Files.getLastModifiedTime(linker).toMillis())
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

    private fun executableName(name: String): String = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "$name.exe" else name
}
