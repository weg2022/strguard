package io.github.weg2022.strguard

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeStaticFieldTest {
    @Test
    fun `static final string fields are restored when class has no clinit`() {
        val original = noClinitFixture()
        val fixture = RuntimeHarness().transformAndLoad(original)

        assertEquals("field-protected-alpha", fixture.stringValue("sample/FieldFixture", "alpha"))
        assertEquals("field-protected-beta", fixture.stringValue("sample/FieldFixture", "beta"))
        assertEquals("field-protected-gamma", fixture.stringValue("sample/FieldFixture", "gamma"))

        fixture.assertCoverage(protectedCount = 3)
        fixture.assertPlaintextAbsent("field-protected-alpha", "field-protected-beta", "field-protected-gamma")
    }

    @Test
    fun `static final string fields are injected before existing clinit initialization`() {
        val original = withClinitFixture()
        val fixture = RuntimeHarness().transformAndLoad(original)

        assertEquals("field-before-counter", fixture.stringValue("sample/ClinitFixture", "beforeCounter"))
        assertEquals("field-after-counter", fixture.stringValue("sample/ClinitFixture", "afterCounter"))
        assertEquals(42, fixture.call("sample/ClinitFixture", "counterValue"))

        fixture.assertCoverage(protectedCount = 2)
        fixture.assertPlaintextAbsent("field-before-counter", "field-after-counter")
    }

    @Test
    fun `interface constants are restored through a synthesized interface clinit`() {
        val iface = ifaceFixture()
        val reader = interfaceReaderFixture()
        val fixture = RuntimeHarness().transformAndLoad(iface, extraFixtures = mapOf("sample/IfaceReader" to reader))

        assertEquals("interface-constant-value", fixture.stringValue("sample/IfaceReader", "read"))
        fixture.assertCoverage(protectedCount = 1)
        fixture.assertPlaintextAbsent("interface-constant-value")
    }

    private fun noClinitFixture(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "sample/FieldFixture", null, "java/lang/Object", null)
        writer.visitField(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
            "ALPHA",
            "Ljava/lang/String;",
            null,
            "field-protected-alpha",
        ).visitEnd()
        writer.visitField(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
            "BETA",
            "Ljava/lang/String;",
            null,
            "field-protected-beta",
        ).visitEnd()
        writer.visitField(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
            "GAMMA",
            "Ljava/lang/String;",
            null,
            "field-protected-gamma",
        ).visitEnd()
        staticGetter(writer, "sample/FieldFixture", "alpha", "ALPHA")
        staticGetter(writer, "sample/FieldFixture", "beta", "BETA")
        staticGetter(writer, "sample/FieldFixture", "gamma", "GAMMA")
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun withClinitFixture(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "sample/ClinitFixture", null, "java/lang/Object", null)
        writer.visitField(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
            "BEFORE_COUNTER",
            "Ljava/lang/String;",
            null,
            "field-before-counter",
        ).visitEnd()
        writer.visitField(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "counter",
            "I",
            null,
            null,
        ).visitEnd()
        writer.visitField(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
            "AFTER_COUNTER",
            "Ljava/lang/String;",
            null,
            "field-after-counter",
        ).visitEnd()
        writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null).apply {
            visitCode()
            visitLdcInsn(42)
            visitFieldInsn(Opcodes.PUTSTATIC, "sample/ClinitFixture", "counter", "I")
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        staticGetter(writer, "sample/ClinitFixture", "beforeCounter", "BEFORE_COUNTER")
        staticGetter(writer, "sample/ClinitFixture", "afterCounter", "AFTER_COUNTER")
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "counterValue", "()I", null, null).apply {
            visitCode()
            visitFieldInsn(Opcodes.GETSTATIC, "sample/ClinitFixture", "counter", "I")
            visitInsn(Opcodes.IRETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun ifaceFixture(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(
            Opcodes.V11,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
            "sample/ConstIface",
            null,
            "java/lang/Object",
            null,
        )
        writer.visitField(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
            "CONSTANT",
            "Ljava/lang/String;",
            null,
            "interface-constant-value",
        ).visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun interfaceReaderFixture(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "sample/IfaceReader", null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "read", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitFieldInsn(Opcodes.GETSTATIC, "sample/ConstIface", "CONSTANT", "Ljava/lang/String;")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun staticGetter(writer: ClassWriter, owner: String, methodName: String, fieldName: String) {
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, methodName, "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitFieldInsn(Opcodes.GETSTATIC, owner, fieldName, "Ljava/lang/String;")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
    }
}
