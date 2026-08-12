package io.github.weg2022.strguard

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RuntimeAnnotationsAndIndyTest {
    @Test
    fun `annotation strings stay plaintext while ldc strings are protected and everything runs`() {
        val fixture = RuntimeHarness().transformAndLoad(annotationFixture())

        assertEquals("protected-ldc-value", fixture.stringValue("sample/CondyFixture", "revealLdc"))
        assertEquals("passed-through", fixture.stringValue("sample/CondyFixture", "echo", "passed-through"))
        assertNull(fixture.call("sample/CondyFixture", "revealCondy"))
        assertEquals("condy-custom-value", fixture.stringValue("sample/CondyFixture", "revealCustomCondy"))

        fixture.assertCoverage(
            protectedCount = 2,
            StringSkipReason.ANNOTATION_STRING to 3L,
            StringSkipReason.CONSTANT_DYNAMIC to 2L,
        )
        fixture.assertPlaintextAbsent("protected-ldc-value")
    }

    private fun annotationFixture(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "sample/CondyFixture", null, "java/lang/Object", null)
        writer.visitAnnotation("Lsample/Label;", true).apply {
            visit("value", "class-annotation-value")
            visitEnd()
        }

        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "revealLdc", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("protected-ldc-value")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }

        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "echo", "(Ljava/lang/String;)Ljava/lang/String;", null, null).apply {
            visitAnnotation("Lsample/Label;", true).apply {
                visit("value", "method-annotation-value")
                visitEnd()
            }
            visitParameterAnnotation(0, "Lsample/Label;", true).apply {
                visit("value", "param-annotation-value")
                visitEnd()
            }
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }

        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "revealCondy", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn(
                ConstantDynamic(
                    "dynamicValue",
                    "Ljava/lang/String;",
                    Handle(
                        Opcodes.H_INVOKESTATIC,
                        "java/lang/invoke/ConstantBootstraps",
                        "nullConstant",
                        "(Ljava/lang/invoke/MethodHandles${'$'}Lookup;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;",
                        false,
                    ),
                ),
            )
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }

        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "revealCustomCondy", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn(
                ConstantDynamic(
                    "customValue",
                    "Ljava/lang/String;",
                    Handle(
                        Opcodes.H_INVOKESTATIC,
                        "sample/CondyFixture",
                        "customBootstrap",
                        "(Ljava/lang/invoke/MethodHandles${'$'}Lookup;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;",
                        false,
                    ),
                ),
            )
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }

        writer.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "customBootstrap",
            "(Ljava/lang/invoke/MethodHandles${'$'}Lookup;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;",
            null,
            null,
        ).apply {
            visitCode()
            visitLdcInsn("condy-custom-value")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }

        writer.visitEnd()
        return writer.toByteArray()
    }
}
