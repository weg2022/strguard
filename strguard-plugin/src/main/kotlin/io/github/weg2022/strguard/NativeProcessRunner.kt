package io.github.weg2022.strguard

import org.gradle.api.GradleException
import java.io.InputStream
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class NativeProcessRunner(
    private val registry: NativeProcessRegistryService,
    private val logCapacity: Int = DEFAULT_NATIVE_LOG_CAPACITY,
    private val inputStreamProvider: (Process) -> InputStream = Process::getInputStream,
) {
    fun run(
        command: List<String>,
        workingDirectory: Path,
        environment: Map<String, String>,
        timeout: Duration,
    ) {
        val process =
            try {
                ProcessBuilder(command)
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(true)
                    .apply {
                        environment().clear()
                        environment().putAll(environment)
                    }.start()
            } catch (failure: Exception) {
                throw GradleException(
                    "Unable to start Native executable '${command.firstOrNull() ?: "unknown"}'",
                    failure,
                )
            }
        registry.register(process)
        val processOutput =
            try {
                inputStreamProvider(process)
            } catch (failure: Throwable) {
                terminateProcessTree(process.toHandle())
                registry.unregister(process)
                throw GradleException("Unable to open ${command.first()} output", failure)
            }
        val output = BoundedLogBuffer(logCapacity)
        val readerFailure = AtomicReference<Throwable?>()
        val reader =
            Thread({
                try {
                    processOutput.use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.append(buffer, 0, read)
                        }
                    }
                } catch (failure: Throwable) {
                    readerFailure.set(failure)
                }
            }, "strguard-native-output-${process.pid()}").apply {
                isDaemon = true
                start()
            }
        try {
            val completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
            if (!completed) {
                terminateProcessTree(process.toHandle())
                process.waitFor(NATIVE_PROCESS_TERMINATION_GRACE.toMillis(), TimeUnit.MILLISECONDS)
                stopReader(reader, processOutput)
                throw GradleException(
                    "${command.first()} timed out after ${timeout.toSeconds()} seconds\n${output.text()}",
                )
            }
            reader.join(NATIVE_READER_JOIN_TIMEOUT.toMillis())
            if (reader.isAlive) {
                stopReader(reader, processOutput)
                throw GradleException("Timed out while reading ${command.first()} output")
            }
            readerFailure.get()?.let { failure ->
                throw GradleException("Unable to read ${command.first()} output", failure)
            }
            if (process.exitValue() != 0) {
                throw GradleException(
                    "${command.first()} exited with code ${process.exitValue()}\n${output.text()}",
                )
            }
        } catch (failure: InterruptedException) {
            terminateProcessTree(process.toHandle())
            Thread.currentThread().interrupt()
            throw GradleException("${command.first()} was interrupted", failure)
        } finally {
            if (process.isAlive) terminateProcessTree(process.toHandle())
            if (reader.isAlive) stopReader(reader, processOutput)
            registry.unregister(process)
        }
    }

    private fun stopReader(reader: Thread, processOutput: InputStream) {
        runCatching(processOutput::close)
        reader.interrupt()
        try {
            reader.join(NATIVE_READER_JOIN_TIMEOUT.toMillis())
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

internal fun nativeProcessEnvironment(extra: Map<String, String>): Map<String, String> {
    val parent = System.getenv()
    val sanitized = linkedMapOf<String, String>()
    NATIVE_ENVIRONMENT_ALLOWLIST.forEach { name -> parent[name]?.let { value -> sanitized[name] = value } }
    sanitized.putAll(extra)
    sanitized.remove(RELEASE_SEED_ENVIRONMENT_VARIABLE)
    return sanitized
}

internal fun rustupHomeDirectory(): String {
    val configured = System.getenv("RUSTUP_HOME")
    if (!configured.isNullOrBlank()) return java.nio.file.Path.of(configured).toAbsolutePath().normalize().toString()
    val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    val primaryHome = if (windows) System.getenv("USERPROFILE") else System.getenv("HOME")
    val secondaryHome = if (windows) System.getenv("HOME") else System.getenv("USERPROFILE")
    val userHome =
        primaryHome?.takeIf(String::isNotBlank)
            ?: secondaryHome?.takeIf(String::isNotBlank)
            ?: System.getProperty("user.home")
    return java.nio.file.Path.of(userHome, ".rustup").toAbsolutePath().normalize().toString()
}

private val NATIVE_ENVIRONMENT_ALLOWLIST =
    setOf(
        "PATH",
        "Path",
        "PATHEXT",
        "ComSpec",
        "SystemRoot",
        "WINDIR",
        "TEMP",
        "TMP",
        "TMPDIR",
        "HOME",
        "USERPROFILE",
        "ProgramFiles",
        "ProgramFiles(x86)",
        "ProgramW6432",
        "CARGO_HOME",
        "RUSTUP_HOME",
        "SSL_CERT_FILE",
        "SSL_CERT_DIR",
        "INCLUDE",
        "LIB",
        "LIBPATH",
        "VCINSTALLDIR",
        "VCToolsInstallDir",
        "WindowsSdkDir",
        "UniversalCRTSdkDir",
        "UCRTVersion",
    )
private const val DEFAULT_NATIVE_LOG_CAPACITY = 256 * 1024
private const val RELEASE_SEED_ENVIRONMENT_VARIABLE = "STRGUARD_RELEASE_SEED_HEX"
private val NATIVE_PROCESS_TERMINATION_GRACE = Duration.ofSeconds(5)
private val NATIVE_READER_JOIN_TIMEOUT = Duration.ofSeconds(5)
