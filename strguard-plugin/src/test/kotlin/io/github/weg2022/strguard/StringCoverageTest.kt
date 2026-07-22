package io.github.weg2022.strguard

import io.github.weg2022.strguard.crypto.CryptoPrimitives
import io.github.weg2022.strguard.vault.SecureVaultBuilder
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertEquals

class StringCoverageTest {
    @Test
    fun `disabled StringConcatFactory reports every literal and static string fragment`() {
        val original = disabledStringConcatFixture()
        val builder =
            SecureVaultBuilder(
                COVERAGE_TEST_SEED,
                "io.github.weg2022:string-coverage::disabled-concat",
                CryptoPrimitives.sha256(original),
                JvmNativeTarget.WINDOWS_X64,
            )

        val result =
            builder.use {
                ClassTransformer.transform(
                    original,
                    TransformSettings(
                        enabled = true,
                        java9StringConcatEnabled = false,
                        strictStringCoverage = true,
                        removeMetadata = false,
                        stringGuardPackages = listOf("sample"),
                        keepStringPackages = emptyList(),
                        removeMetadataPackages = emptyList(),
                        keepMetadataPackages = emptyList(),
                    ),
                    builder,
                )
            }

        assertEquals(0, result.stringCoverage.protectedStrings)
        assertEquals(4, result.stringCoverage.skipped(StringSkipReason.DISABLED_STRING_CONCAT))
        assertEquals(4, result.stringCoverage.skippedStrings)
        assertEquals(4, result.stringCoverage.strictViolations)
    }

    @Test
    fun `coverage records protected and unsupported string shapes without metadata noise`() {
        val original = coverageFixture()
        val builder =
            SecureVaultBuilder(
                COVERAGE_TEST_SEED,
                "io.github.weg2022:string-coverage::test",
                CryptoPrimitives.sha256(original),
                JvmNativeTarget.WINDOWS_X64,
            )

        val result =
            builder.use {
                ClassTransformer.transform(
                    original,
                    TransformSettings(
                        enabled = true,
                        java9StringConcatEnabled = true,
                        strictStringCoverage = false,
                        removeMetadata = false,
                        stringGuardPackages = listOf("sample"),
                        keepStringPackages = emptyList(),
                        removeMetadataPackages = emptyList(),
                        keepMetadataPackages = emptyList(),
                    ),
                    builder,
                )
            }

        with(result.stringCoverage) {
            assertEquals(2, protectedStrings)
            assertEquals(2, skipped(StringSkipReason.EMPTY_STRING))
            assertEquals(1, skipped(StringSkipReason.OVERSIZED_STRING))
            assertEquals(1, skipped(StringSkipReason.ANNOTATION_STRING))
            assertEquals(1, skipped(StringSkipReason.CONSTANT_DYNAMIC))
            assertEquals(1, skipped(StringSkipReason.UNSUPPORTED_INVOKEDYNAMIC))
            assertEquals(1, skipped(StringSkipReason.UNSUPPORTED_FIELD_STRING))
            assertEquals(0, coverageUnknowns)
            assertEquals(5, strictViolations)
            assertEquals(9, encounteredStrings)
        }
    }

    private fun coverageFixture(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "sample/CoverageFixture", null, "java/lang/Object", null)
        writer.visitAnnotation("Lsample/Label;", true).apply {
            visit("value", "annotation-sensitive-value")
            visitEnd()
        }
        writer.visitAnnotation("Lkotlin/Metadata;", true).apply {
            visitArray("d1").apply {
                visit(null, "compiler-metadata-is-not-an-application-string")
                visitEnd()
            }
            visitEnd()
        }
        writer.visitField(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
            "PROTECTED",
            "Ljava/lang/String;",
            null,
            "field-sensitive-value",
        ).visitEnd()
        writer.visitField(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
            "EMPTY",
            "Ljava/lang/String;",
            null,
            "",
        ).visitEnd()
        writer.visitField(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
            "OVERSIZED",
            "Ljava/lang/String;",
            null,
            "x".repeat(30_001),
        ).visitEnd()
        writer.visitField(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
            "UNSUPPORTED",
            "Ljava/lang/String;",
            null,
            "unsupported-field-value",
        ).visitEnd()
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "values", "()V", null, null).apply {
            visitCode()
            visitLdcInsn("ldc-sensitive-value")
            visitInsn(Opcodes.POP)
            visitLdcInsn("")
            visitInsn(Opcodes.POP)
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
            visitInsn(Opcodes.POP)
            visitLdcInsn(
                ConstantDynamic(
                    "dynamicNumber",
                    "I",
                    Handle(
                        Opcodes.H_INVOKESTATIC,
                        "java/lang/invoke/ConstantBootstraps",
                        "nullConstant",
                        "(Ljava/lang/invoke/MethodHandles${'$'}Lookup;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;",
                        false,
                    ),
                ),
            )
            visitInsn(Opcodes.POP)
            visitInvokeDynamicInsn(
                "dynamicString",
                "()Ljava/lang/String;",
                Handle(
                    Opcodes.H_INVOKESTATIC,
                    "sample/Bootstrap",
                    "bootstrap",
                    "(Ljava/lang/invoke/MethodHandles${'$'}Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;)Ljava/lang/invoke/CallSite;",
                    false,
                ),
                "invokedynamic-sensitive-value",
            )
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun disabledStringConcatFixture(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "sample/DisabledConcatFixture", null, "java/lang/Object", null)
        writer.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "concat",
            "(Ljava/lang/String;)Ljava/lang/String;",
            null,
            null,
        ).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitInvokeDynamicInsn(
                "makeConcatWithConstants",
                "(Ljava/lang/String;)Ljava/lang/String;",
                Handle(
                    Opcodes.H_INVOKESTATIC,
                    "java/lang/invoke/StringConcatFactory",
                    "makeConcatWithConstants",
                    "(Ljava/lang/invoke/MethodHandles${'$'}Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                    false,
                ),
                "disabled-prefix-\u0001-disabled-middle-\u0002-disabled-suffix",
                "disabled-static-value",
            )
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }
}

private const val COVERAGE_TEST_SEED =
    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
