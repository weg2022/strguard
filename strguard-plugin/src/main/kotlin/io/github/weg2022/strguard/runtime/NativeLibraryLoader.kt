package io.github.weg2022.strguard.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.ArrayList
import java.util.regex.Pattern

/** Extracts a bundled Native library for a generated bridge class. */
object NativeLibraryLoader {
    @JvmStatic
    @Suppress("unused", "ConvertTryFinallyToUseCall") // Invoked by the generated ASM bridge.
    fun extract(anchor: Class<*>, resourcePath: String, fileName: String): String {
        requireValidResource(resourcePath, fileName)
        val classLoader = anchor.classLoader ?: ClassLoader.getSystemClassLoader()
        val resource = classLoader.getResourceAsStream(resourcePath)
            ?: throw IllegalStateException("Missing StrGuard Native runtime resource '$resourcePath'")
        val root = tempRoot()
        Files.createDirectories(root)
        cleanPreviousExtractions(root)
        val directory = Files.createTempDirectory(root, EXTRACTION_PREFIX)
        val library = directory.resolve(fileName)
        // Kotlin's use() would add a kotlin-stdlib dependency to the injected runtime helper.
        try {
            Files.copy(resource, library, StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: Throwable) {
            deleteExtraction(library)
            throw failure
        } finally {
            resource.close()
        }
        return library.toAbsolutePath().toString()
    }

    /** Deletes an extracted library after loading where the host OS permits it. */
    @JvmStatic
    @Suppress("unused") // Invoked by the generated ASM bridge.
    fun loaded(path: String) {
        val library = Path.of(path).toAbsolutePath().normalize()
        val directory = library.parent ?: return
        val root = tempRoot()
        if (!directory.parent.equals(root) || !EXTRACTION_DIRECTORY.matcher(directory.fileName.toString()).matches()) return
        if (!isGeneratedLibraryFile(library.fileName.toString())) return
        deleteExtraction(library)
    }

    private fun requireValidResource(resourcePath: String, fileName: String) {
        require(isGeneratedLibraryFile(fileName)) { "Invalid StrGuard Native library file name '$fileName'" }
        require(GENERATED_RESOURCE_PATH.matcher(resourcePath).matches()) {
            "Invalid StrGuard Native runtime resource path '$resourcePath'"
        }
    }

    private fun cleanPreviousExtractions(root: Path) {
        val directories = Files.newDirectoryStream(root, "$EXTRACTION_PREFIX*")
        try {
            var inspected = 0
            for (directory in directories) {
                if (inspected++ >= MAX_CLEANUP_DIRECTORIES) break
                if (!Files.isDirectory(directory)) continue
                val entries = ArrayList<Path>()
                val children = Files.newDirectoryStream(directory)
                var safe = true
                try {
                    for (entry in children) {
                        if (!Files.isRegularFile(entry) || !isGeneratedLibraryFile(entry.fileName.toString())) {
                            safe = false
                            break
                        }
                        entries.add(entry)
                    }
                } catch (_: Exception) {
                    safe = false
                } finally {
                    children.close()
                }
                if (!safe) continue
                for (entry in entries) {
                    deleteQuietly(entry)
                }
                deleteQuietly(directory)
            }
        } finally {
            directories.close()
        }
    }

    private fun deleteExtraction(library: Path) {
        try {
            Files.deleteIfExists(library)
        } catch (_: Exception) {
            return
        }
        try {
            Files.deleteIfExists(library.parent)
        } catch (_: Exception) {
            // Cleanup is retried on the next extraction.
        }
    }

    private fun tempRoot(): Path = Path.of(System.getProperty("java.io.tmpdir"), TEMP_ROOT_NAME).toAbsolutePath().normalize()

    private fun isGeneratedLibraryFile(fileName: String): Boolean = GENERATED_LIBRARY_FILE.matcher(fileName).matches()

    private fun deleteQuietly(path: Path): Boolean = try {
        Files.deleteIfExists(path)
    } catch (_: Exception) {
        false
    }

    private val GENERATED_LIBRARY_FILE = Pattern.compile("(?:lib)?sg_[0-9a-f]{24}[.](?:dll|so|dylib)")
    private val GENERATED_RESOURCE_PATH =
        Pattern.compile("META-INF/strguard/native/[A-Za-z0-9_.-]+/(?:lib)?sg_[0-9a-f]{24}[.](?:dll|so|dylib)")
    private val EXTRACTION_DIRECTORY = Pattern.compile("sg2-[A-Za-z0-9._-]+")
    private const val TEMP_ROOT_NAME = "strguard"
    private const val EXTRACTION_PREFIX = "sg2-"
    private const val MAX_CLEANUP_DIRECTORIES = 64
}
