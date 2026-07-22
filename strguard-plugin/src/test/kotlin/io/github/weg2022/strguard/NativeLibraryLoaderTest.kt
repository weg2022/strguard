package io.github.weg2022.strguard

import io.github.weg2022.strguard.runtime.NativeLibraryLoader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlin.test.*

class NativeLibraryLoaderTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private lateinit var originalTemporaryDirectory: String

    @BeforeEach
    fun useIsolatedTemporaryDirectory() {
        originalTemporaryDirectory = System.getProperty("java.io.tmpdir")
        System.setProperty("java.io.tmpdir", temporaryDirectory.toString())
    }

    @AfterEach
    fun restoreTemporaryDirectory() {
        System.setProperty("java.io.tmpdir", originalTemporaryDirectory)
    }

    @Test
    fun `injected loader targets Java 11 and has no Kotlin runtime references`() {
        val bytes =
            assertNotNull(
                NativeLibraryLoader::class.java.getResourceAsStream("NativeLibraryLoader.class"),
            ).use { input -> input.readBytes() }

        assertEquals(55, readUnsignedShort(bytes, 6))
        assertFalse(bytes.toString(StandardCharsets.ISO_8859_1).contains("kotlin/"))
    }

    @Test
    fun `missing runtime resource fails closed`() {
        val fileName = "libsg_000000000000000000000000.so"
        val resourcePath = "META-INF/strguard/native/missing/$fileName"

        val failure = assertFailsWith<IllegalStateException> {
            NativeLibraryLoader.extract(
                NativeLibraryLoaderTest::class.java,
                resourcePath,
                fileName,
                VALID_MARKER_PATH,
            )
        }

        assertContains(failure.message.orEmpty(), "No valid StrGuard Native runtime resource container")
    }

    @Test
    fun `extract preserves other directories and loaded removes only its own extraction`() {
        val root = Path.of(System.getProperty("java.io.tmpdir"), "strguard").toAbsolutePath().normalize()
        Files.createDirectories(root)
        val staleDirectory = Files.createTempDirectory(root, "sg-")
        val staleLibrary = staleDirectory.resolve("libsg_222222222222222222222222.so")
        Files.writeString(staleLibrary, "stale")

        val fileName = "libsg_111111111111111111111111.so"
        val extracted =
            Path.of(
                NativeLibraryLoader.extract(
                    NativeLibraryLoaderTest::class.java,
                    "META-INF/strguard/native/test/$fileName",
                    fileName,
                    VALID_MARKER_PATH,
                ),
            )

        assertTrue(Files.isRegularFile(staleLibrary))
        assertTrue(Files.isRegularFile(extracted))
        assertEquals("fixture", Files.readString(extracted).trim())
        if (root.fileSystem.supportedFileAttributeViews().contains("posix")) {
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(root))
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(extracted.parent))
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(extracted))
        }
        NativeLibraryLoader.loaded(extracted.toString())
        assertFalse(Files.exists(extracted))
        assertFalse(Files.exists(extracted.parent))
    }

    @Test
    fun `extract rejects a Native resource whose hash does not match its marker`() {
        val fileName = "libsg_111111111111111111111111.so"

        val failure = assertFailsWith<IllegalStateException> {
            NativeLibraryLoader.extract(
                NativeLibraryLoaderTest::class.java,
                "META-INF/strguard/native/test/$fileName",
                fileName,
                "META-INF/strguard/artifacts/22222222222222222222222222222222.properties",
            )
        }

        assertContains(failure.message.orEmpty(), "No valid StrGuard Native runtime resource container")
    }

    @Test
    fun `extract supports anchor classes and Native resources in separate classpath directories`() {
        val classes = temporaryDirectory.resolve("classes")
        val resources = temporaryDirectory.resolve("resources")
        writeAnchorClass(classes)
        writeRuntimeContainer(resources)

        URLClassLoader(arrayOf(classes.toUri().toURL(), resources.toUri().toURL()), null).use { loader ->
            val anchor = loader.loadClass(TEST_ANCHOR_CLASS.replace('/', '.'))
            val extracted =
                Path.of(
                    NativeLibraryLoader.extract(
                        anchor,
                        TEST_RESOURCE_PATH,
                        TEST_FILE_NAME,
                        TEST_MARKER_PATH,
                    ),
                )
            assertEquals(TEST_NATIVE_CONTENT, Files.readString(extracted))
            NativeLibraryLoader.loaded(extracted.toString())
        }
    }

    @Test
    fun `extract rejects multiple valid resource containers`() {
        val classes = temporaryDirectory.resolve("classes")
        val first = temporaryDirectory.resolve("first")
        val second = temporaryDirectory.resolve("second")
        writeAnchorClass(classes)
        writeRuntimeContainer(first)
        writeRuntimeContainer(second)

        URLClassLoader(
            arrayOf(classes.toUri().toURL(), first.toUri().toURL(), second.toUri().toURL()),
            null,
        ).use { loader ->
            val anchor = loader.loadClass(TEST_ANCHOR_CLASS.replace('/', '.'))
            val failure = assertFailsWith<IllegalStateException> {
                NativeLibraryLoader.extract(anchor, TEST_RESOURCE_PATH, TEST_FILE_NAME, TEST_MARKER_PATH)
            }
            assertContains(failure.message.orEmpty(), "Multiple valid StrGuard Native runtime resource containers")
        }
    }

    @Test
    fun `extract never pairs a Native binary and marker from different containers`() {
        val classes = temporaryDirectory.resolve("classes")
        val binaryContainer = temporaryDirectory.resolve("binary")
        val markerContainer = temporaryDirectory.resolve("marker")
        writeAnchorClass(classes)
        writeRuntimeBinary(binaryContainer)
        writeRuntimeMarker(markerContainer)

        URLClassLoader(
            arrayOf(classes.toUri().toURL(), binaryContainer.toUri().toURL(), markerContainer.toUri().toURL()),
            null,
        ).use { loader ->
            val anchor = loader.loadClass(TEST_ANCHOR_CLASS.replace('/', '.'))
            val failure = assertFailsWith<IllegalStateException> {
                NativeLibraryLoader.extract(anchor, TEST_RESOURCE_PATH, TEST_FILE_NAME, TEST_MARKER_PATH)
            }
            assertContains(failure.message.orEmpty(), "No valid StrGuard Native runtime resource container")
        }
    }

    @Test
    fun `rejects untrusted extraction paths`() {
        assertFailsWith<IllegalArgumentException> {
            NativeLibraryLoader.extract(
                NativeLibraryLoaderTest::class.java,
                "META-INF/strguard/native/test/../../payload.dll",
                "../../payload.dll",
                VALID_MARKER_PATH,
            )
        }
    }

    private fun writeAnchorClass(root: Path) {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, TEST_ANCHOR_CLASS, null, "java/lang/Object", null)
        writer.visitEnd()
        val output = root.resolve("$TEST_ANCHOR_CLASS.class")
        Files.createDirectories(output.parent)
        Files.write(output, writer.toByteArray())
    }

    private fun writeRuntimeContainer(root: Path) {
        writeRuntimeBinary(root)
        writeRuntimeMarker(root)
    }

    private fun writeRuntimeBinary(root: Path) {
        val binary = root.resolve(TEST_RESOURCE_PATH)
        Files.createDirectories(binary.parent)
        Files.writeString(binary, TEST_NATIVE_CONTENT)
    }

    private fun writeRuntimeMarker(root: Path) {
        val hash =
            MessageDigest.getInstance("SHA-256").digest(TEST_NATIVE_CONTENT.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
        val marker = root.resolve(TEST_MARKER_PATH)
        Files.createDirectories(marker.parent)
        Files.writeString(
            marker,
            """
            schemaVersion=1
            artifactId=$TEST_ARTIFACT_ID
            stage=protected
            runtimeFamily=jvm
            runtimeTarget=test
            bridgeClass=$TEST_ANCHOR_CLASS
            loaderClass=io/github/weg2022/strguard/runtime/NativeLibraryLoader
            nativeResources=$TEST_RESOURCE_PATH:$hash
            """.trimIndent() + "\n",
        )
    }

    private fun readUnsignedShort(bytes: ByteArray, offset: Int): Int = ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
}

private const val VALID_MARKER_PATH =
    "META-INF/strguard/artifacts/11111111111111111111111111111111.properties"
private const val TEST_ARTIFACT_ID = "33333333333333333333333333333333"
private const val TEST_ANCHOR_CLASS = "sample/LoaderAnchor"
private const val TEST_FILE_NAME = "libsg_333333333333333333333333.so"
private const val TEST_RESOURCE_PATH = "META-INF/strguard/native/test/$TEST_FILE_NAME"
private const val TEST_MARKER_PATH = "META-INF/strguard/artifacts/$TEST_ARTIFACT_ID.properties"
private const val TEST_NATIVE_CONTENT = "container-specific-native-fixture"
