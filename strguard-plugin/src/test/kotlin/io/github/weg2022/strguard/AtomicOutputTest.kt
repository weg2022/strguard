package io.github.weg2022.strguard

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AtomicOutputTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `commits file and directory outputs as one set`() {
        val stagedFile = temporaryDirectory.resolve("stage/output.jar")
        val stagedDirectory = temporaryDirectory.resolve("stage/classes")
        val outputFile = temporaryDirectory.resolve("build/output.jar")
        val outputDirectory = temporaryDirectory.resolve("build/classes")
        Files.createDirectories(stagedFile.parent)
        Files.writeString(stagedFile, "new-file")
        Files.createDirectories(stagedDirectory)
        Files.writeString(stagedDirectory.resolve("new.class"), "new-directory")
        Files.createDirectories(outputFile.parent)
        Files.writeString(outputFile, "old-file")
        Files.createDirectories(outputDirectory)
        Files.writeString(outputDirectory.resolve("old.class"), "old-directory")

        replaceOutputsAtomically(
            OutputReplacement(stagedFile, outputFile),
            OutputReplacement(stagedDirectory, outputDirectory),
        )

        assertEquals("new-file", Files.readString(outputFile))
        assertEquals("new-directory", Files.readString(outputDirectory.resolve("new.class")))
        assertFalse(Files.exists(outputDirectory.resolve("old.class")))
        assertFalse(Files.exists(outputFile.resolveSibling(".output.jar.strguard-backup")))
        assertFalse(Files.exists(outputDirectory.resolveSibling(".classes.strguard-backup")))
    }

    @Test
    fun `missing staged output leaves all existing outputs untouched`() {
        val stagedFile = temporaryDirectory.resolve("stage/output.jar")
        val missingDirectory = temporaryDirectory.resolve("stage/missing")
        val outputFile = temporaryDirectory.resolve("build/output.jar")
        val outputDirectory = temporaryDirectory.resolve("build/classes")
        Files.createDirectories(stagedFile.parent)
        Files.writeString(stagedFile, "new-file")
        Files.createDirectories(outputFile.parent)
        Files.writeString(outputFile, "old-file")
        Files.createDirectories(outputDirectory)
        Files.writeString(outputDirectory.resolve("old.class"), "old-directory")

        assertFailsWith<IllegalStateException> {
            replaceOutputsAtomically(
                OutputReplacement(stagedFile, outputFile),
                OutputReplacement(missingDirectory, outputDirectory),
            )
        }

        assertEquals("old-file", Files.readString(outputFile))
        assertEquals("old-directory", Files.readString(outputDirectory.resolve("old.class")))
        assertTrue(Files.isRegularFile(stagedFile))
    }
}
