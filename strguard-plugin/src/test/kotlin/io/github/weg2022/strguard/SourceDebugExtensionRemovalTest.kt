package io.github.weg2022.strguard

import io.github.weg2022.strguard.crypto.CryptoPrimitives
import io.github.weg2022.strguard.vault.SecureVaultBuilder
import org.objectweb.asm.*
import kotlin.test.*

/**
 * 验证 SourceDebugExtension 移除语义：
 * - kotlin.Metadata 永远保留(透传且其字符串值不被当作应用字符串)
 * - SourceDebugExtension/DebugMetadata 注解 + SMAP 属性在开启时移除
 * - @KeepSourceDebugExtension 豁免、开关关闭时全部原样保留
 */
class SourceDebugExtensionRemovalTest {
    private val metadataAnnotation = "Lkotlin/Metadata;"
    private val sourceDebugAnnotation = "Lkotlin/jvm/internal/SourceDebugExtension;"
    private val debugMetadataAnnotation = "Lkotlin/coroutines/jvm/internal/DebugMetadata;"
    private val keepAnnotation = "Lio/github/weg2022/strguard/annotation/KeepSourceDebugExtension;"

    @Test
    fun `enabled removal strips debug annotations and SMAP but retains kotlin Metadata`() {
        val result = transform(removalFixture(), remove = true)

        val annotations = readAnnotations(result.bytes)
        assertTrue(metadataAnnotation in annotations, "kotlin.Metadata 必须保留")
        assertFalse(sourceDebugAnnotation in annotations, "SourceDebugExtension 注解应移除")
        assertFalse(debugMetadataAnnotation in annotations, "DebugMetadata 注解应移除")
        assertNull(readSourceDebug(result.bytes), "SMAP 属性应移除")
        assertNotNull(readSourceFile(result.bytes), "SourceFile 应保留")
        // kotlin.Metadata 的 d1 字符串值不得计入应用字符串
        assertEquals(0, result.stringCoverage.skipped(StringSkipReason.ANNOTATION_STRING))
        assertEquals(
            setOf(
                "sample/SdeFixture $sourceDebugAnnotation",
                "sample/SdeFixture $debugMetadataAnnotation",
                "sample/SdeFixture SourceDebugExtension",
            ),
            result.removedSourceDebugExtensions,
        )
    }

    @Test
    fun `disabled removal keeps every annotation and the SMAP attribute`() {
        val result = transform(removalFixture(), remove = false)

        val annotations = readAnnotations(result.bytes)
        assertTrue(metadataAnnotation in annotations)
        assertTrue(sourceDebugAnnotation in annotations, "关闭时 SourceDebugExtension 注解应保留")
        assertTrue(debugMetadataAnnotation in annotations, "关闭时 DebugMetadata 注解应保留")
        assertNotNull(readSourceDebug(result.bytes), "关闭时 SMAP 属性应保留")
        assertTrue(result.removedSourceDebugExtensions.isEmpty())
    }

    @Test
    fun `KeepSourceDebugExtension annotation exempts a class from removal`() {
        val result = transform(removalFixture(withKeepAnnotation = true), remove = true)

        val annotations = readAnnotations(result.bytes)
        assertTrue(metadataAnnotation in annotations)
        assertTrue(sourceDebugAnnotation in annotations, "豁免类应保留 SourceDebugExtension 注解")
        assertTrue(debugMetadataAnnotation in annotations, "豁免类应保留 DebugMetadata 注解")
        assertTrue(keepAnnotation in annotations)
        assertNotNull(readSourceDebug(result.bytes), "豁免类应保留 SMAP 属性")
        assertTrue(result.removedSourceDebugExtensions.isEmpty())
    }

    private fun transform(classBytes: ByteArray, remove: Boolean): ClassTransformResult {
        val builder =
            SecureVaultBuilder(
                SDE_REMOVAL_TEST_SEED,
                "io.github.weg2022:sde-removal::test",
                CryptoPrimitives.sha256(classBytes),
                JvmNativeTarget.WINDOWS_X64,
            )
        return builder.use {
            ClassTransformer.transform(
                classBytes,
                TransformSettings(
                    enabled = true,
                    java9StringConcatEnabled = true,
                    strictStringCoverage = false,
                    removeSourceDebugExtension = remove,
                    stringGuardPackages = listOf("sample"),
                    keepStringPackages = emptyList(),
                    removeSourceDebugExtensionPackages = listOf("sample"),
                    keepSourceDebugExtensionPackages = emptyList(),
                ),
                builder,
                ClassTransformer::class.java.classLoader,
            )
        }
    }

    private fun removalFixture(withKeepAnnotation: Boolean = false): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "sample/SdeFixture", null, "java/lang/Object", null)
        writer.visitSource(
            "SdeFixture.kt",
            "SMAP\nSdeFixture.kt\nKotlin\n*S Kotlin\n*F\n+ 1 SdeFixture.kt\nsample/SdeFixture.kt\n*L\n1#1,3:1\n*E\n",
        )
        writer.visitAnnotation(metadataAnnotation, true).apply {
            visitArray("d1").apply {
                visit(null, "compiler-metadata-is-not-an-application-string")
                visitEnd()
            }
            visitEnd()
        }
        writer.visitAnnotation(sourceDebugAnnotation, true).apply {
            visit("value", "SMAP\nembedded-annotation-form\n")
            visitEnd()
        }
        writer.visitAnnotation(debugMetadataAnnotation, true).apply {
            visit("c", "sample/SdeFixture")
            visit("f", "suspendValue")
            visit("l", 1)
            visit("i", 1)
            visit("s", "Ljava/lang/String;")
            visit("m", "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;")
            visitEnd()
        }
        if (withKeepAnnotation) {
            writer.visitAnnotation(keepAnnotation, true).visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun readAnnotations(bytes: ByteArray): Set<String> {
        val found = linkedSetOf<String>()
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? {
                    found += requireNotNull(descriptor)
                    return null
                }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        return found
    }

    // 哨兵 null 区分"未调用 visitSource"与"以 null 调用";不可用 SKIP_DEBUG,否则跳过回调
    private fun readSourceDebug(bytes: ByteArray): String? {
        var debug: String? = null
        var visited = false
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitSource(source: String?, debugValue: String?) {
                    visited = true
                    debug = debugValue
                }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES,
        )
        return if (visited) debug else null
    }

    private fun readSourceFile(bytes: ByteArray): String? {
        var source: String? = null
        var visited = false
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitSource(sourceValue: String?, debug: String?) {
                    visited = true
                    source = sourceValue
                }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES,
        )
        return if (visited) source else null
    }
}

private const val SDE_REMOVAL_TEST_SEED =
    "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
