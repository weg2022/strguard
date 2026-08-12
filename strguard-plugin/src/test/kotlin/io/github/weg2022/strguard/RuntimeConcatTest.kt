package io.github.weg2022.strguard

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 覆盖两类拼接产物的运行时端到端验证：
 * 1. Java 9+ `StringConcatFactory.makeConcatWithConstants`（javac 的 `+` 拼接）——全参数矩阵
 * 2. `StringBuilder` append 序列（Kotlin 字符串模板的编译形态，如 `"Hello, $name!"`）
 */
class RuntimeConcatTest {
    @Test
    fun `concat with every dynamic argument type matches JDK semantics`() {
        val fixture = RuntimeHarness().transformAndLoad(concatFixture())

        assertEquals("pre-value-post", fixture.stringValue("sample/ConcatFixture", "concatString", "value"))
        assertEquals("true", fixture.stringValue("sample/ConcatFixture", "concatBoolean", true))
        assertEquals("A", fixture.stringValue("sample/ConcatFixture", "concatChar", 'A'))
        assertEquals("5", fixture.stringValue("sample/ConcatFixture", "concatByte", 5.toByte()))
        assertEquals("300", fixture.stringValue("sample/ConcatFixture", "concatShort", 300.toShort()))
        assertEquals("42", fixture.stringValue("sample/ConcatFixture", "concatInt", 42))
        assertEquals("1234567890123", fixture.stringValue("sample/ConcatFixture", "concatLong", 1_234_567_890_123L))
        assertEquals("0.5", fixture.stringValue("sample/ConcatFixture", "concatFloat", 0.5f))
        assertEquals("1.5", fixture.stringValue("sample/ConcatFixture", "concatDouble", 1.5))
        assertEquals("obj", fixture.stringValue("sample/ConcatFixture", "concatObject", "obj"))
        assertEquals("null", fixture.stringValue("sample/ConcatFixture", "concatNull", null))
        val arrayResult = fixture.stringValue("sample/ConcatFixture", "concatArray", arrayOf("a", "b"))
        assertTrue(arrayResult.startsWith("[Ljava.lang.String;@"), "array must stringify as identity, was '$arrayResult'")
        assertEquals("hello", fixture.stringValue("sample/ConcatFixture", "concatChars", charArrayOf('h', 'e', 'l', 'l', 'o')))

        fixture.assertCoverage(protectedCount = 16)
    }

    @Test
    fun `concat with static string and number arguments matches JDK semantics`() {
        val fixture = RuntimeHarness().transformAndLoad(concatFixture())

        assertEquals("adynstatic-value", fixture.stringValue("sample/ConcatFixture", "concatStatic", "dyn"))
        assertEquals("5424344.045.0", fixture.stringValue("sample/ConcatFixture", "concatStaticNumbers", 5))
        assertEquals("pure-literal-value", fixture.stringValue("sample/ConcatFixture", "pureLiteral"))

        fixture.assertCoverage(protectedCount = 16)
    }

    @Test
    fun `string template shape built with StringBuilder appends matches Kotlin semantics`() {
        val fixture = RuntimeHarness().transformAndLoad(concatFixture())

        assertEquals("Hello, world!", fixture.stringValue("sample/ConcatFixture", "sbConcat", "world"))
        assertEquals("a=1 b=2", fixture.stringValue("sample/ConcatFixture", "sbMultiTemplate", 1, 2))
        assertEquals("Hello, !", fixture.stringValue("sample/ConcatFixture", "sbConcat", ""))

        fixture.assertCoverage(protectedCount = 16)
    }

    @Test
    fun `concat across control flow boundaries keeps stack and frame consistency`() {
        val fixture = RuntimeHarness().transformAndLoad(concatFixture())

        assertEquals("branch-a-one", fixture.stringValue("sample/ConcatFixture", "branchMerge", true))
        assertEquals("branch-b-two", fixture.stringValue("sample/ConcatFixture", "branchMerge", false))
        assertEquals("x0x1x2", fixture.stringValue("sample/ConcatFixture", "loopConcat", 3))
        assertEquals("outer-inner-inner", fixture.stringValue("sample/ConcatFixture", "nestedConcat", "inner"))
        assertEquals("123.0", fixture.stringValue("sample/ConcatFixture", "mixedSlots", 1, 2L, 3.0))
    }

    @Test
    fun `concat rewrite removes every literal from class bytes`() {
        val fixture = RuntimeHarness().transformAndLoad(concatFixture())

        fixture.assertPlaintextAbsent(
            "pre-",
            "-post",
            "astatic-value",
            "static-value",
            "pure-literal-value",
            "branch-a-",
            "branch-b-",
            "outer-",
            "inner-",
            "Hello, ",
            "a=",
            " b=",
        )
    }

    private fun concatFixture(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "sample/ConcatFixture", null, "java/lang/Object", null)

        singleDynamic(writer, "concatString", "(Ljava/lang/String;)Ljava/lang/String;", "pre--post")
        singleDynamic(writer, "concatBoolean", "(Z)Ljava/lang/String;", "")
        singleDynamic(writer, "concatChar", "(C)Ljava/lang/String;", "")
        singleDynamic(writer, "concatByte", "(B)Ljava/lang/String;", "")
        singleDynamic(writer, "concatShort", "(S)Ljava/lang/String;", "")
        singleDynamic(writer, "concatInt", "(I)Ljava/lang/String;", "")
        singleDynamic(writer, "concatLong", "(J)Ljava/lang/String;", "")
        singleDynamic(writer, "concatFloat", "(F)Ljava/lang/String;", "")
        singleDynamic(writer, "concatDouble", "(D)Ljava/lang/String;", "")
        singleDynamic(writer, "concatObject", "(Ljava/lang/Object;)Ljava/lang/String;", "")
        singleDynamic(writer, "concatNull", "(Ljava/lang/Object;)Ljava/lang/String;", "")
        singleDynamic(writer, "concatArray", "([Ljava/lang/String;)Ljava/lang/String;", "")
        singleDynamic(writer, "concatChars", "([C)Ljava/lang/String;", "")

        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "concatStatic", "(Ljava/lang/String;)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            invokeConcat("(Ljava/lang/String;)Ljava/lang/String;", "a", "static-value")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "concatStaticNumbers", "(I)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ILOAD, 0)
            invokeConcat("(I)Ljava/lang/String;", "", 42, 43L, 44.0f, 45.0)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "pureLiteral", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            invokeConcat("()Ljava/lang/String;", "pure-literal-value")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }

        // Kotlin 字符串模板编译形态：StringBuilder append 序列（如 `"Hello, $name!"`），
        // StringBuilder 引用保持在栈上（javac/Kotlin 产物形态）
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "sbConcat", "(Ljava/lang/String;)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder")
            visitInsn(Opcodes.DUP)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false)
            visitLdcInsn("Hello, ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false)
            visitLdcInsn("!")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "sbMultiTemplate", "(II)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder")
            visitInsn(Opcodes.DUP)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false)
            visitLdcInsn("a=")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false)
            visitVarInsn(Opcodes.ILOAD, 0)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", false)
            visitLdcInsn(" b=")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false)
            visitVarInsn(Opcodes.ILOAD, 1)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", false)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }

        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "branchMerge", "(Z)Ljava/lang/String;", null, null).apply {
            visitCode()
            val elseLabel = Label()
            val endLabel = Label()
            visitVarInsn(Opcodes.ILOAD, 0)
            visitJumpInsn(Opcodes.IFEQ, elseLabel)
            visitLdcInsn("one")
            invokeConcat("(Ljava/lang/String;)Ljava/lang/String;", "branch-a-")
            visitJumpInsn(Opcodes.GOTO, endLabel)
            visitLabel(elseLabel)
            visitLdcInsn("two")
            invokeConcat("(Ljava/lang/String;)Ljava/lang/String;", "branch-b-")
            visitLabel(endLabel)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }

        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "loopConcat", "(I)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitInsn(Opcodes.ICONST_0)
            visitVarInsn(Opcodes.ISTORE, 1)
            visitLdcInsn("")
            visitVarInsn(Opcodes.ASTORE, 2)
            val loopStart = Label()
            val loopEnd = Label()
            visitLabel(loopStart)
            visitVarInsn(Opcodes.ILOAD, 1)
            visitVarInsn(Opcodes.ILOAD, 0)
            visitJumpInsn(Opcodes.IF_ICMPGE, loopEnd)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitVarInsn(Opcodes.ILOAD, 1)
            invokeConcat("(Ljava/lang/String;I)Ljava/lang/String;", "x")
            visitVarInsn(Opcodes.ASTORE, 2)
            visitIincInsn(1, 1)
            visitJumpInsn(Opcodes.GOTO, loopStart)
            visitLabel(loopEnd)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }

        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "nestedConcat", "(Ljava/lang/String;)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            invokeConcat("(Ljava/lang/String;)Ljava/lang/String;", "inner-")
            invokeConcat("(Ljava/lang/String;)Ljava/lang/String;", "outer-")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }

        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "mixedSlots", "(IJD)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ILOAD, 0)
            visitVarInsn(Opcodes.LLOAD, 1)
            visitVarInsn(Opcodes.DLOAD, 3)
            invokeConcat("(IJD)Ljava/lang/String;", "")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }

        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun singleDynamic(
        writer: ClassWriter,
        methodName: String,
        descriptor: String,
        recipe: String,
    ) {
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, methodName, descriptor, null, null).apply {
            visitCode()
            visitVarInsn(loadOpcode(descriptor), 0)
            invokeConcat(descriptor, recipe)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
    }

    private fun loadOpcode(descriptor: String): Int = when {
        descriptor.startsWith("(L") || descriptor.startsWith("([") -> Opcodes.ALOAD
        descriptor.startsWith("(J") -> Opcodes.LLOAD
        descriptor.startsWith("(F") -> Opcodes.FLOAD
        descriptor.startsWith("(D") -> Opcodes.DLOAD
        else -> Opcodes.ILOAD
    }

    private fun MethodVisitor.invokeConcat(descriptor: String, recipe: String, vararg bootstrapArguments: Any?) {
        visitInvokeDynamicInsn(
            "makeConcatWithConstants",
            descriptor,
            Handle(
                Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/StringConcatFactory",
                "makeConcatWithConstants",
                "(Ljava/lang/invoke/MethodHandles${'$'}Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                false,
            ),
            recipe,
            *bootstrapArguments,
        )
    }
}
