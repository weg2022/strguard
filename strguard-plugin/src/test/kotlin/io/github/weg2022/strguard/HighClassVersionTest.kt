package io.github.weg2022.strguard

import io.github.weg2022.strguard.crypto.CryptoPrimitives
import io.github.weg2022.strguard.vault.SecureVaultBuilder
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HighClassVersionTest {
    @Test
    fun `Java 11 through Java 27 classes are transformed without changing their versions`() {
        SUPPORTED_CLASS_VERSIONS.forEach { version ->
            val majorVersion = version and 0xffff
            val original = classWithVersion(version, "sample/Java$majorVersion")
            val transformed = transform(original, "java-$majorVersion")

            assertEquals(0, readUnsignedShort(transformed, 4), "minor version for Java major $majorVersion")
            assertEquals(majorVersion, readUnsignedShort(transformed, 6))
            assertEquals("sample/Java$majorVersion", ClassReader(transformed).className)
            assertFalse(
                transformed.toString(StandardCharsets.ISO_8859_1).contains(HIGH_CLASS_VERSION_SECRET),
                "plaintext remains in Java major $majorVersion class",
            )
        }
    }

    @Test
    fun `Java 27 preview class is transformed without changing its version`() {
        val original = classWithVersion(Opcodes.V27 or Opcodes.V_PREVIEW, "sample/Java27Preview")
        val transformed = transform(original, "java-27-preview")

        assertEquals(0xffff, readUnsignedShort(transformed, 4))
        assertEquals(71, readUnsignedShort(transformed, 6))
        assertEquals("sample/Java27Preview", ClassReader(transformed).className)
        assertFalse(transformed.toString(StandardCharsets.ISO_8859_1).contains(HIGH_CLASS_VERSION_SECRET))
    }

    private fun transform(original: ByteArray, identity: String): ByteArray {
        val builder =
            SecureVaultBuilder(
                HIGH_CLASS_VERSION_TEST_SEED,
                "io.github.weg2022:high-class-version::$identity",
                CryptoPrimitives.sha256(original),
                JvmNativeTarget.WINDOWS_X64,
            )

        return builder.use {
            ClassTransformer.transform(
                original,
                TransformSettings(
                    enabled = true,
                    java9StringConcatEnabled = true,
                    removeMetadata = false,
                    stringGuardPackages = listOf("sample"),
                    keepStringPackages = emptyList(),
                    removeMetadataPackages = emptyList(),
                    keepMetadataPackages = emptyList(),
                ),
                builder,
            ).bytes
        }
    }

    private fun classWithVersion(version: Int, internalName: String): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(
            version,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
            internalName,
            null,
            "java/lang/Object",
            null,
        )
        writer.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "reveal",
            "()Ljava/lang/String;",
            null,
            null,
        ).apply {
            visitCode()
            visitLdcInsn(HIGH_CLASS_VERSION_SECRET)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun readUnsignedShort(bytes: ByteArray, offset: Int): Int = ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
}

private const val HIGH_CLASS_VERSION_SECRET = "java-27-preview-sensitive-value"
private const val HIGH_CLASS_VERSION_TEST_SEED =
    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
private val SUPPORTED_CLASS_VERSIONS =
    listOf(
        Opcodes.V11,
        Opcodes.V12,
        Opcodes.V13,
        Opcodes.V14,
        Opcodes.V15,
        Opcodes.V16,
        Opcodes.V17,
        Opcodes.V18,
        Opcodes.V19,
        Opcodes.V20,
        Opcodes.V21,
        Opcodes.V22,
        Opcodes.V23,
        Opcodes.V24,
        Opcodes.V25,
        Opcodes.V26,
        Opcodes.V27,
    )
