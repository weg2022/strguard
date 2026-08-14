package io.github.weg2022.strguard

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiProtectionVerifierTest {
    private val settings = TransformSettings(
        enabled = true,
        java9StringConcatEnabled = true,
        strictStringCoverage = false,
        removeSourceDebugExtension = false,
        stringGuardPackages = listOf("sample"),
        keepStringPackages = emptyList(),
        removeSourceDebugExtensionPackages = emptyList(),
        keepSourceDebugExtensionPackages = emptyList(),
        aiPolicyEnabled = true,
        aiPolicyPackages = listOf("sample"),
    )

    @Test
    fun `single class scan reports protected verified policy and version`() {
        val bytes = RuntimeHarness().transformAndLoad(fixture("sample/App"), settings).transformedBytes

        val state = AiProtectionVerifier.verifyClassBytes(bytes)
        assertTrue(state.isProtected)
        assertTrue(state.isVerified)
        assertTrue(state.attributeCount > 0)
        assertEquals(AiPolicyMarker.POLICY_NAME, state.policy)
        assertEquals(AiPolicyMarker.MARKER_VERSION, state.version)
        assertEquals("sample/App", state.className)
    }

    @Test
    fun `unmarked class is missing`() {
        val state = AiProtectionVerifier.verifyClassBytes(fixture("sample/Unmarked"))

        assertFalse(state.isProtected)
        assertFalse(state.isVerified)
        assertEquals(0, state.attributeCount)
    }

    @Test
    fun `duplicate class annotations are detected`() {
        val duplicate = ClassReader(fixture("sample/Duplicate")).let { reader ->
            ClassWriter(0).also { writer ->
                reader.accept(
                    object : ClassVisitor(Opcodes.ASM9, writer) {
                        override fun visitEnd() {
                            repeat(2) {
                                super.visitAnnotation(AiPolicyMarker.ANNOTATION_DESCRIPTOR, false)?.visitEnd()
                            }
                            super.visitEnd()
                        }
                    },
                    0,
                )
            }.toByteArray()
        }

        val state = AiProtectionVerifier.verifyClassBytes(duplicate)
        assertTrue(state.isDuplicate)
        assertEquals(2, state.classAnnotationCount)
    }

    @Test
    fun `directory scan aggregates statistics`() {
        val directory = createTempDirectory("strguard-verify-")
        Files.createDirectories(directory.resolve("sample"))
        Files.write(directory.resolve("sample/Marked.class"), RuntimeHarness().transformAndLoad(fixture("sample/Marked"), settings).transformedBytes)
        Files.write(directory.resolve("sample/AlsoMarked.class"), RuntimeHarness().transformAndLoad(fixture("sample/AlsoMarked"), settings).transformedBytes)
        Files.write(directory.resolve("sample/Unmarked.class"), fixture("sample/Unmarked"))

        val result = AiProtectionVerifier.verifyDirectory(directory)
        assertEquals(3, result.scannedClasses)
        assertEquals(2, result.protectedClasses)
        assertEquals(2, result.verifiedClasses)
        assertEquals(1, result.missingMarkerClasses)
        assertEquals(0, result.duplicateMarkerClasses)
        assertEquals(mapOf("${AiPolicyMarker.POLICY_NAME} v${AiPolicyMarker.MARKER_VERSION}" to 2), result.policyVersionDistribution)
        assertEquals(listOf("sample/Unmarked"), result.failedClasses)
    }

    @Test
    fun `require protected passes when every class carries the marker`() {
        val directory = createTempDirectory("strguard-all-marked-")
        Files.createDirectories(directory.resolve("sample"))
        Files.write(directory.resolve("sample/Marked.class"), RuntimeHarness().transformAndLoad(fixture("sample/Marked"), settings).transformedBytes)

        AiProtectionVerifier.requireProtected(AiProtectionVerifier.verifyDirectory(directory))
    }

    private fun fixture(internalName: String): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        writer.visitField(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
            "CONSTANT",
            "Ljava/lang/String;",
            null,
            "fixture-field-value",
        ).visitEnd()
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("fixture-literal")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }
}
