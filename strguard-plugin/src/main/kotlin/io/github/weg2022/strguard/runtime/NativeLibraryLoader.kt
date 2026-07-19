package io.github.weg2022.strguard.runtime

import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Extracts a bundled Native library for a generated bridge class. */
object NativeLibraryLoader {
    @JvmStatic
    @Suppress("unused", "ConvertTryFinallyToUseCall") // Invoked by the generated ASM bridge.
    fun extract(anchor: Class<*>, resourcePath: String, fileName: String): String {
        val classLoader = anchor.classLoader ?: ClassLoader.getSystemClassLoader()
        val resource = classLoader.getResourceAsStream(resourcePath)
            ?: throw IllegalStateException("Missing StrGuard Native runtime resource '$resourcePath'")
        val directory = Files.createTempDirectory("sg2-")
        val library = directory.resolve(fileName)
        // Kotlin's use() would add a kotlin-stdlib dependency to the injected runtime helper.
        try {
            Files.copy(resource, library, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            resource.close()
        }
        library.toFile().deleteOnExit()
        directory.toFile().deleteOnExit()
        return library.toAbsolutePath().toString()
    }
}
