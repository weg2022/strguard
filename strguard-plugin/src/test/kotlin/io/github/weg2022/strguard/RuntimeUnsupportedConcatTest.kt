package io.github.weg2022.strguard

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeUnsupportedConcatTest {
    @Test
    fun `disabled string concat is forwarded to the JVM and still runs`() {
        val original = fixture("(Ljava/lang/String;)Ljava/lang/String;") { method ->
            method.visitVarInsn(Opcodes.ALOAD, 0)
            method.visitInvokeDynamicInsn(
                "makeConcatWithConstants",
                "(Ljava/lang/String;)Ljava/lang/String;",
                concatWithConstantsHandle(),
                "disabled-prefix--disabled-middle--disabled-suffix",
                "disabled-static-value",
            )
        }
        val fixture = RuntimeHarness().transformAndLoad(original, disabledSettings())

        assertEquals(
            "disabled-prefix-dyn-disabled-middle-disabled-static-value-disabled-suffix",
            fixture.stringValue("sample/UnsupportedConcatFixture", "concat", "dyn"),
        )
        fixture.assertCoverage(
            protectedCount = 0,
            StringSkipReason.DISABLED_STRING_CONCAT to 4L,
        )
    }

    @Test
    fun `makeConcat variant without recipe is classified as unsupported string concat`() {
        val original = fixture("(Ljava/lang/String;I)Ljava/lang/String;") { method ->
            method.visitVarInsn(Opcodes.ALOAD, 0)
            method.visitVarInsn(Opcodes.ILOAD, 1)
            method.visitInvokeDynamicInsn(
                "makeConcat",
                "(Ljava/lang/String;I)Ljava/lang/String;",
                Handle(
                    Opcodes.H_INVOKESTATIC,
                    "java/lang/invoke/StringConcatFactory",
                    "makeConcat",
                    "(Ljava/lang/invoke/MethodHandles${'$'}Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                    false,
                ),
            )
        }
        val fixture = RuntimeHarness().transformAndLoad(original)

        assertEquals("dyn42", fixture.stringValue("sample/UnsupportedConcatFixture", "concat", "dyn", 42))
        fixture.assertCoverage(
            protectedCount = 0,
            StringSkipReason.UNSUPPORTED_STRING_CONCAT to 1L,
        )
    }

    @Test
    fun `concat with constant dynamic static argument is skipped but runs`() {
        val condySalt =
            ConstantDynamic(
                "salt",
                "Ljava/lang/String;",
                Handle(
                    Opcodes.H_INVOKESTATIC,
                    "sample/UnsupportedConcatFixture",
                    "condyBootstrap",
                    "(Ljava/lang/invoke/MethodHandles${'$'}Lookup;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;",
                    false,
                ),
            )
        val original = fixture("(Ljava/lang/String;)Ljava/lang/String;", withCondyBootstrap = true) { method ->
            method.visitVarInsn(Opcodes.ALOAD, 0)
            method.visitInvokeDynamicInsn(
                "makeConcatWithConstants",
                "(Ljava/lang/String;)Ljava/lang/String;",
                concatWithConstantsHandle(),
                "condy--",
                condySalt,
            )
        }
        val fixture = RuntimeHarness().transformAndLoad(original)

        assertEquals("condy-dyn-salt-value", fixture.stringValue("sample/UnsupportedConcatFixture", "concat", "dyn"))
        fixture.assertCoverage(
            protectedCount = 1,
            StringSkipReason.UNSUPPORTED_STRING_CONCAT to 2L,
            StringSkipReason.CONSTANT_DYNAMIC to 1L,
        )
    }

    private fun disabledSettings(): TransformSettings = TransformSettings(
        enabled = true,
        java9StringConcatEnabled = false,
        strictStringCoverage = true,
        removeSourceDebugExtension = false,
        stringGuardPackages = listOf("sample"),
        keepStringPackages = emptyList(),
        removeSourceDebugExtensionPackages = emptyList(),
        keepSourceDebugExtensionPackages = emptyList(),
    )

    private fun fixture(
        methodDescriptor: String,
        withCondyBootstrap: Boolean = false,
        concatWriter: (MethodVisitor) -> Unit,
    ): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "sample/UnsupportedConcatFixture", null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "concat", methodDescriptor, null, null).apply {
            visitCode()
            concatWriter(this)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        if (withCondyBootstrap) {
            writer.visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                "condyBootstrap",
                "(Ljava/lang/invoke/MethodHandles${'$'}Lookup;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;",
                null,
                null,
            ).apply {
                visitCode()
                visitLdcInsn("salt-value")
                visitInsn(Opcodes.ARETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun concatWithConstantsHandle(): Handle = Handle(
        Opcodes.H_INVOKESTATIC,
        "java/lang/invoke/StringConcatFactory",
        "makeConcatWithConstants",
        "(Ljava/lang/invoke/MethodHandles${'$'}Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
        false,
    )
}
