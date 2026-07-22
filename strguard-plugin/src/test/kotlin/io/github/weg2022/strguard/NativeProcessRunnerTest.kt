package io.github.weg2022.strguard

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeProcessRunnerTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `bounded log buffer retains only the newest bytes`() {
        val buffer = BoundedLogBuffer(8)
        buffer.append("first".toByteArray(), 0, 5)
        val second = "-second".toByteArray()
        buffer.append(second, 0, second.size)

        assertEquals("t-second", buffer.text())
    }

    @Test
    fun `native environment strips the release seed and unrelated variables`() {
        val environment =
            nativeProcessEnvironment(
                mapOf(
                    "STRGUARD_RELEASE_SEED_HEX" to "must-not-escape",
                    "STRGUARD_TEST_EXTRA" to "explicit",
                ),
            )

        assertFalse("STRGUARD_RELEASE_SEED_HEX" in environment)
        assertEquals("explicit", environment["STRGUARD_TEST_EXTRA"])
        assertFalse("JAVA_TOOL_OPTIONS" in environment)
    }

    @Test
    fun `reproducible Rust flags remap task paths and stabilize MSVC linking`() {
        val buildRoot = temporaryDirectory.resolve("native build root")
        val normalizedRoot = buildRoot.toAbsolutePath().normalize().toString()
        val portableRoot = normalizedRoot.replace('\\', '/')

        val linuxFlags = encodedReproducibleRustFlags(buildRoot, "x86_64-unknown-linux-gnu").split('\u001f')
        assertEquals("--remap-path-prefix=$normalizedRoot=strguard-native", linuxFlags.first())
        if (portableRoot != normalizedRoot) {
            assertEquals("--remap-path-prefix=$portableRoot=strguard-native", linuxFlags.single { portableRoot in it })
        }
        assertFalse(linuxFlags.any { it.contains("link-arg") })

        val windowsFlags = encodedReproducibleRustFlags(buildRoot, "x86_64-pc-windows-msvc").split('\u001f')
        assertEquals("-Clink-arg=/Brepro", windowsFlags.last())
    }

    @Test
    fun `runner times out and terminates the process`() {
        val failure =
            assertFailsWith<GradleException> {
                runner().run(
                    command = fixtureCommand("sleep"),
                    workingDirectory = temporaryDirectory,
                    environment = nativeProcessEnvironment(emptyMap()),
                    timeout = Duration.ofMillis(200),
                )
            }

        assertTrue(failure.message.orEmpty().contains("timed out"))
    }

    @Test
    fun `runner keeps a bounded tail for failed commands`() {
        val failure =
            assertFailsWith<GradleException> {
                NativeProcessRunner(processRegistry(), logCapacity = 128).run(
                    command = fixtureCommand("output"),
                    workingDirectory = temporaryDirectory,
                    environment = nativeProcessEnvironment(emptyMap()),
                    timeout = Duration.ofSeconds(10),
                )
            }

        assertTrue(failure.message.orEmpty().contains("STRGUARD_OUTPUT_TAIL"))
        assertTrue(failure.message.orEmpty().length < 512)
    }

    @Test
    fun `runner drains 100 MiB output without exceeding bounded tail`() {
        val failure =
            assertFailsWith<GradleException> {
                NativeProcessRunner(processRegistry(), logCapacity = 128).run(
                    command = fixtureCommand("large-output"),
                    workingDirectory = temporaryDirectory,
                    environment = nativeProcessEnvironment(emptyMap()),
                    timeout = Duration.ofSeconds(30),
                )
            }

        assertTrue(failure.message.orEmpty().contains("STRGUARD_LARGE_OUTPUT_TAIL"))
        assertTrue(failure.message.orEmpty().length < 512)
    }

    @Test
    fun `timeout terminates descendant process tree`() {
        val failure =
            assertFailsWith<GradleException> {
                runner().run(
                    command = fixtureCommand("descendant"),
                    workingDirectory = temporaryDirectory,
                    environment = nativeProcessEnvironment(emptyMap()),
                    timeout = Duration.ofSeconds(2),
                )
            }

        assertTrue(failure.message.orEmpty().contains("timed out"))
        val childPid = Files.readString(temporaryDirectory.resolve("child.pid")).trim().toLong()
        assertProcessStops(childPid)
    }

    @Test
    fun `thread interruption cancels process and reader`() {
        val failure = AtomicReference<Throwable?>()
        val worker =
            Thread {
                try {
                    runner().run(
                        command = fixtureCommand("identified-sleep"),
                        workingDirectory = temporaryDirectory,
                        environment = nativeProcessEnvironment(emptyMap()),
                        timeout = Duration.ofSeconds(30),
                    )
                } catch (caught: Throwable) {
                    failure.set(caught)
                }
            }
        worker.start()
        val pidFile = temporaryDirectory.resolve("process.pid")
        val processPid = waitForProcessId(pidFile)

        worker.interrupt()
        worker.join(10_000)

        assertFalse(worker.isAlive)
        assertTrue(failure.get() is GradleException)
        assertTrue(failure.get()?.message.orEmpty().contains("was interrupted"))
        assertProcessStops(processPid)
    }

    @Test
    fun `runner supports working directory containing spaces`() {
        val workingDirectory = temporaryDirectory.resolve("native path with spaces")
        Files.createDirectories(workingDirectory)

        runner().run(
            command = fixtureCommand("path"),
            workingDirectory = workingDirectory,
            environment = nativeProcessEnvironment(emptyMap()),
            timeout = Duration.ofSeconds(10),
        )

        assertEquals("ok", Files.readString(workingDirectory.resolve("path fixture.txt")))
    }

    @Test
    fun `runner reports output stream failure`() {
        val runner =
            NativeProcessRunner(
                registry = processRegistry(),
                inputStreamProvider = {
                    object : InputStream() {
                        override fun read(): Int = throw IOException("fixture stream failure")
                    }
                },
            )

        val failure =
            assertFailsWith<GradleException> {
                runner.run(
                    command = fixtureCommand("success"),
                    workingDirectory = temporaryDirectory,
                    environment = nativeProcessEnvironment(emptyMap()),
                    timeout = Duration.ofSeconds(10),
                )
            }

        assertTrue(failure.message.orEmpty().contains("Unable to read"))
        assertTrue(failure.cause?.message.orEmpty().contains("fixture stream failure"))
    }

    private fun runner(): NativeProcessRunner = NativeProcessRunner(processRegistry())

    private fun processRegistry(): NativeProcessRegistryService = ProjectBuilder.builder().build().gradle.sharedServices.registerIfAbsent(
        "nativeProcessRunnerTest-${System.nanoTime()}",
        NativeProcessRegistryService::class.java,
    ) {}.get()

    private fun fixtureCommand(mode: String): List<String> = listOf(
        Path.of(System.getProperty("java.home"), "bin", if (isWindows()) "java.exe" else "java").toString(),
        "-cp",
        System.getProperty("java.class.path"),
        NativeProcessFixture::class.java.name,
        mode,
    )

    private fun waitForProcessId(path: Path): Long {
        repeat(100) {
            if (Files.isRegularFile(path)) {
                runCatching { Files.readString(path).trim().toLongOrNull() }
                    .getOrNull()
                    ?.let { pid -> return pid }
            }
            Thread.sleep(50)
        }
        throw AssertionError("Process fixture did not write a valid PID to $path")
    }

    private fun assertProcessStops(pid: Long) {
        repeat(100) {
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false).not()) return
            Thread.sleep(50)
        }
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "Process $pid is still alive")
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
}

object NativeProcessFixture {
    @JvmStatic
    fun main(arguments: Array<String>) {
        when (arguments.first()) {
            "sleep" -> Thread.sleep(30_000)

            "identified-sleep" -> {
                Files.writeString(Path.of("process.pid"), ProcessHandle.current().pid().toString())
                Thread.sleep(30_000)
            }

            "descendant" -> {
                val java = Path.of(System.getProperty("java.home"), "bin", if (isFixtureWindows()) "java.exe" else "java")
                val child =
                    ProcessBuilder(
                        java.toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        NativeProcessFixture::class.java.name,
                        "sleep",
                    ).start()
                Files.writeString(Path.of("child.pid"), child.pid().toString())
                Thread.sleep(30_000)
            }

            "output" -> {
                print("x".repeat(10_000))
                print("STRGUARD_OUTPUT_TAIL")
                kotlin.system.exitProcess(3)
            }

            "large-output" -> {
                val chunk = ByteArray(1024 * 1024) { 'x'.code.toByte() }
                repeat(100) { System.out.write(chunk) }
                print("STRGUARD_LARGE_OUTPUT_TAIL")
                kotlin.system.exitProcess(3)
            }

            "path" -> Files.writeString(Path.of("path fixture.txt"), "ok")

            "success" -> Unit
        }
    }

    private fun isFixtureWindows(): Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
}
