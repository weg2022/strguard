package io.github.weg2022.strguard

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 回归测试：concat 重写引入的 newLocal 临时槽与原始局部变量槽位冲突。
 *
 * ASM 9.7+ 重写后的 LocalVariablesSorter，newLocalMapping 不再把新槽位注册进
 * remappedVariableIndices，因此若临时槽的 store/load 经 super.visitVarInsn 发射，
 * 会被 remap() 按"原始局部变量索引"重定向——当方法里恰有同编号的原始局部变量
 * 且其重映射已建立时，临时值会覆盖仍然存活的原始变量（类型不同则直接产生
 * VerifyError，ProGuard 优化阶段报 VariableEmptySlotException/VariableTypeException）。
 * 修复后临时槽绕过 LVS 重映射直发底层 visitor（与 GeneratorAdapter 同款做法）。
 *
 * 两个 fixture 方法手工构造出确定性的碰撞点：静态无参方法（firstLocal=0），
 * 原始槽位按首次出现顺序被 remap 占用 nextLocal 计数，使 concat 重写分配到的
 * newLocal 编号恰好等于某个"已重映射、值仍存活"的原始槽位编号。
 */
class StringConcatSlotCollisionTest {
    @Test
    fun `string builder temporary slot must not clobber a live int local`() {
        val fixture = RuntimeHarness().transformAndLoad(collisionFixture())

        // 预修复：sbLocal=newLocal(OBJECT)=2 经 super.visitVarInsn 被 remap 到槽 1，
        // 覆盖存活的 int，随后的 ILOAD 读到 StringBuilder 引用 → defineClass 抛 VerifyError。
        // 修复后槽 2 直发，int 幸存于槽 1，返回 ICONST_1 的值。
        assertEquals(1, fixture.call("sample/SlotCollisionFixture", "sbTempVsLiveInt"))
    }

    @Test
    fun `dynamic int argument slot must not clobber a live object local`() {
        val fixture = RuntimeHarness().transformAndLoad(collisionFixture())

        // 预修复：dynamicLocals 的 newLocal(INT)=2 经 remap 落到槽 0，
        // 覆盖存活的 "live-object" 字符串（LDC 经 gateway 保护后存入），
        // 随后的 ALOAD 读到 int → defineClass 抛 VerifyError。
        // 修复后槽 2 直发，字符串幸存于槽 0。
        assertEquals("live-object", fixture.stringValue("sample/SlotCollisionFixture", "intArgVsLiveObject"))
    }

    private fun collisionFixture(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "sample/SlotCollisionFixture", null, "java/lang/Object", null)

        // 静态无参：firstLocal=0。ISTORE 1 → remap 到 0（nextLocal=1），
        // ISTORE 2 → remap 到 1（nextLocal=2）→ sbLocal=newLocal=2 与原始槽 2 同编号。
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "sbTempVsLiveInt", "()I", null, null).apply {
            visitCode()
            visitInsn(Opcodes.ICONST_0)
            visitVarInsn(Opcodes.ISTORE, 1)
            visitInsn(Opcodes.ICONST_1)
            visitVarInsn(Opcodes.ISTORE, 2)
            invokeConcat("()Ljava/lang/String;", "x")
            visitInsn(Opcodes.POP)
            visitVarInsn(Opcodes.ILOAD, 2)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(0, 0)
            visitEnd()
        }

        // ASTORE 2（OBJECT）→ remap 到 0（nextLocal=1），ISTORE 3（INT）→ remap 到 1（nextLocal=2）
        // → 动态 int 参数 newLocal=2 与原始槽 2 同编号，预修复被 remap 到槽 0。
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "intArgVsLiveObject", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("live-object")
            visitVarInsn(Opcodes.ASTORE, 2)
            visitIntInsn(Opcodes.BIPUSH, 42)
            visitVarInsn(Opcodes.ISTORE, 3)
            visitVarInsn(Opcodes.ILOAD, 3)
            invokeConcat("(I)Ljava/lang/String;", "\u0001")
            visitInsn(Opcodes.POP)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }

        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun MethodVisitor.invokeConcat(descriptor: String, recipe: String) {
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
        )
    }
}
