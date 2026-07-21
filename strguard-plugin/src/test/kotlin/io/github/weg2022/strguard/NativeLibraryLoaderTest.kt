package io.github.weg2022.strguard

import io.github.weg2022.strguard.runtime.NativeLibraryLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class NativeLibraryLoaderTest {
    @Test
    fun `missing runtime resource reports requested path`() {
        val fileName = "libsg_000000000000000000000000.so"
        val resourcePath = "META-INF/strguard/native/missing/$fileName"

        val failure = assertFailsWith<IllegalStateException> {
            NativeLibraryLoader.extract(NativeLibraryLoaderTest::class.java, resourcePath, fileName)
        }

        assertContains(failure.message.orEmpty(), resourcePath)
    }

    @Test
    fun `extract cleans bounded stale directory and loaded removes current extraction`() {
        val root = Path.of(System.getProperty("java.io.tmpdir"), "strguard").toAbsolutePath().normalize()
        Files.createDirectories(root)
        val staleDirectory = Files.createTempDirectory(root, "sg2-")
        val staleLibrary = staleDirectory.resolve("libsg_222222222222222222222222.so")
        Files.writeString(staleLibrary, "stale")

        val fileName = "libsg_111111111111111111111111.so"
        val extracted =
            Path.of(
                NativeLibraryLoader.extract(
                    NativeLibraryLoaderTest::class.java,
                    "META-INF/strguard/native/test/$fileName",
                    fileName,
                ),
            )

        assertFalse(Files.exists(staleDirectory))
        assertTrue(Files.isRegularFile(extracted))
        assertEquals("fixture", Files.readString(extracted).trim())
        NativeLibraryLoader.loaded(extracted.toString())
        assertFalse(Files.exists(extracted))
        assertFalse(Files.exists(extracted.parent))
    }

    @Test
    fun `rejects untrusted extraction paths`() {
        assertFailsWith<IllegalArgumentException> {
            NativeLibraryLoader.extract(
                NativeLibraryLoaderTest::class.java,
                "META-INF/strguard/native/test/../../payload.dll",
                "../../payload.dll",
            )
        }
    }
}
