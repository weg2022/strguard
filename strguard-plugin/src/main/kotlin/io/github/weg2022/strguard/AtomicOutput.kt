package io.github.weg2022.strguard

import org.gradle.api.GradleException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal fun replaceOutputsAtomically(vararg replacements: OutputReplacement) {
    replacements.forEach { replacement ->
        check(Files.exists(replacement.staged)) { "StrGuard staged output is missing: ${replacement.staged}" }
        Files.createDirectories(requireNotNull(replacement.output.parent))
        deletePath(replacement.backup)
    }
    val backedUp = mutableListOf<OutputReplacement>()
    val committed = mutableListOf<OutputReplacement>()
    try {
        replacements.forEach { replacement ->
            if (Files.exists(replacement.output)) {
                moveOutput(replacement.output, replacement.backup)
                backedUp += replacement
            }
        }
        replacements.forEach { replacement ->
            moveOutput(replacement.staged, replacement.output)
            committed += replacement
        }
    } catch (failure: Throwable) {
        committed.asReversed().forEach { replacement ->
            runCatching { deletePath(replacement.output) }.onFailure(failure::addSuppressed)
        }
        backedUp.asReversed().forEach { replacement ->
            runCatching { moveOutput(replacement.backup, replacement.output) }.onFailure(failure::addSuppressed)
        }
        throw GradleException("StrGuard cannot atomically replace transform outputs", failure)
    }
    backedUp.forEach { replacement -> deletePath(replacement.backup) }
}

internal data class OutputReplacement(val staged: Path, val output: Path) {
    val backup: Path = output.resolveSibling(".${output.fileName}.strguard-backup")
}

private fun moveOutput(staged: Path, output: Path) {
    try {
        Files.move(
            staged,
            output,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(staged, output, StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun deletePath(path: Path) {
    if (!Files.exists(path)) return
    if (Files.isDirectory(path)) {
        if (!path.toFile().deleteRecursively()) {
            throw GradleException("StrGuard cannot delete output path $path")
        }
    } else {
        Files.delete(path)
    }
}
