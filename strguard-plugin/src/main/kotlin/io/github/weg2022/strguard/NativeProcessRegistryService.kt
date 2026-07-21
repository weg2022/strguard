package io.github.weg2022.strguard

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.util.concurrent.ConcurrentHashMap

abstract class NativeProcessRegistryService :
    BuildService<BuildServiceParameters.None>,
    AutoCloseable {
    private val processes = ConcurrentHashMap<Long, ProcessHandle>()

    internal fun register(process: Process) {
        processes[process.pid()] = process.toHandle()
    }

    internal fun unregister(process: Process) {
        processes.remove(process.pid())
    }

    override fun close() {
        processes.values.forEach(::terminateProcessTree)
        processes.clear()
    }
}

internal fun terminateProcessTree(root: ProcessHandle) {
    val descendants = root.descendants().use { processes -> processes.iterator().asSequence().toList() }.asReversed()
    descendants.forEach { process -> runCatching(process::destroy) }
    runCatching(root::destroy)
    descendants.forEach { process ->
        if (process.isAlive) runCatching(process::destroyForcibly)
    }
    if (root.isAlive) runCatching(root::destroyForcibly)
}
