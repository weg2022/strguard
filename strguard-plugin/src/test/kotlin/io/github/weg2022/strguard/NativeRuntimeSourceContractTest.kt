package io.github.weg2022.strguard

import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.test.*

class NativeRuntimeSourceContractTest {
    @Test
    fun `bundled runtime uses an Android-compatible JNI version`() {
        val resource = assertNotNull(
            javaClass.classLoader.getResource("strguard-native-runtime/src/lib.rs"),
        )
        val source = resource.readText()

        assertContains(source, "JNI_VERSION_1_6")
        assertFalse(source.contains("JNI_VERSION_1_8"))
    }

    @Test
    fun `bundled vendor archive is fixed and contains only safe entries`() {
        val bytes = assertNotNull(
            javaClass.classLoader.getResourceAsStream("strguard-native-runtime/vendor.zip"),
        ).use { it.readBytes() }
        assertEquals(VENDOR_ARCHIVE_SHA256, sha256(bytes))

        var hasCargoConfig = false
        var crateChecksums = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val path = Path.of(entry.name).normalize()
                assertFalse(path.isAbsolute, entry.name)
                assertFalse(path.startsWith(".."), entry.name)
                hasCargoConfig = hasCargoConfig || entry.name == ".cargo/config.toml"
                if (entry.name.endsWith("/.cargo-checksum.json")) crateChecksums++
            }
        }
        assertTrue(hasCargoConfig)
        assertEquals(54, crateChecksums, "Expected one checksum manifest per locked third-party crate")
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte -> "%02x".format(byte) }
}

private const val VENDOR_ARCHIVE_SHA256 =
    "fd55940d6ad8ba059cc8db47b970aedca2c1b12a8b660b200ae19debda550d2a"
