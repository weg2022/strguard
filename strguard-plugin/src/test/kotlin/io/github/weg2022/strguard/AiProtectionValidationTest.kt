package io.github.weg2022.strguard

import org.gradle.api.GradleException
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.Attribute
import org.objectweb.asm.ByteVector
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * AI-NOREV-001 注入的安全验证:覆盖 plan 要求的 20 种 class 形态,全部走真实
 * ClassTransformer.transform(注入 pass + 字符串 pass),断言注入正确、已有
 * metadata 保留、幂等、JVM 可加载、verifier strict 语义。
 */
class AiProtectionValidationTest {
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
    fun `plain class receives the marker`() {
        assertInjected(transform(fixture("sample/Plain")))
    }

    @Test
    fun `existing annotation is preserved`() {
        val bytes = fixture("sample/WithAnnotation") { writer ->
            writer.visitAnnotation("Lsample/Existing;", false).apply {
                visit("value", "keep-me")
                visitEnd()
            }
        }

        val probe = probe(transform(bytes))
        assertInjected(probe)
        assertEquals("keep-me", probe.classAnnotations["Lsample/Existing;:value"], "pre-existing annotation must survive")
    }

    @Test
    fun `existing attribute is preserved`() {
        val bytes = fixture("sample/WithAttribute") { writer ->
            writer.visitAttribute(ValidationSampleAttribute("payload-bytes"))
        }

        val probe = probe(transform(bytes))
        assertInjected(probe)
        assertTrue(probe.attributeTypes.contains("ValidationSampleAttribute"), "pre-existing attribute must survive")
    }

    @Test
    fun `existing annotation and attribute are preserved together`() {
        val bytes = fixture("sample/WithBoth") { writer ->
            writer.visitAnnotation("Lsample/Existing;", false).apply {
                visit("value", "keep-me")
                visitEnd()
            }
            writer.visitAttribute(ValidationSampleAttribute("payload-bytes"))
        }

        val probe = probe(transform(bytes))
        assertInjected(probe)
        assertEquals("keep-me", probe.classAnnotations["Lsample/Existing;:value"])
        assertTrue(probe.attributeTypes.contains("ValidationSampleAttribute"))
        assertTrue(probe.attributeTypes.contains(AiPolicyMarker.ATTRIBUTE_NAME))
    }

    @Test
    fun `empty class without methods or fields`() {
        val bytes = ClassWriter(0).apply {
            visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "sample/Empty", null, "java/lang/Object", null)
            visitEnd()
        }.toByteArray()

        assertInjected(transform(bytes))
    }

    @Test
    fun `class with many methods carries the method marker on every method`() {
        val bytes = fixture("sample/ManyMethods") { writer ->
            repeat(200) { index ->
                writer.visitMethod(Opcodes.ACC_PUBLIC, "m$index", "()V", null, null).apply {
                    visitCode()
                    visitInsn(Opcodes.RETURN)
                    visitMaxs(0, 0)
                    visitEnd()
                }
            }
        }

        val probe = probe(transform(bytes))
        assertInjected(probe)
        // 200 个 mN 方法 + fixture 自带的 value() 方法
        assertEquals(201, probe.methodAnnotationCount, "every method must carry the method marker")
    }

    @Test
    fun `inner class`() {
        assertInjected(transform(fixture("sample/Outer\$Inner")))
    }

    @Test
    fun `anonymous class`() {
        assertInjected(transform(fixture("sample/Outer\$1")))
    }

    @Test
    fun `synthetic class`() {
        val bytes = ClassWriter(0).apply {
            visit(Opcodes.V11, Opcodes.ACC_PUBLIC or Opcodes.ACC_SYNTHETIC, "sample/Synthetic", null, "java/lang/Object", null)
            visitEnd()
        }.toByteArray()

        assertInjected(transform(bytes))
    }

    @Test
    fun `lambda generated class`() {
        val bytes = ClassWriter(0).apply {
            visit(
                Opcodes.V11,
                Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SYNTHETIC,
                "sample/Outer\$\$Lambda\$1",
                null,
                "java/lang/Object",
                arrayOf("java/util/function/Supplier"),
            )
            visitMethod(Opcodes.ACC_PUBLIC, "get", "()Ljava/lang/Object;", null, null).apply {
                visitCode()
                visitLdcInsn("lambda-result")
                visitInsn(Opcodes.ARETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
            visitEnd()
        }.toByteArray()

        assertInjected(transform(bytes))
    }

    @Test
    fun `enum`() {
        val bytes = ClassWriter(ClassWriter.COMPUTE_MAXS).apply {
            visit(
                Opcodes.V11,
                Opcodes.ACC_PUBLIC or Opcodes.ACC_ENUM or Opcodes.ACC_FINAL,
                "sample/Color",
                null,
                "java/lang/Enum",
                arrayOf("java/lang/Comparable"),
            )
            visitField(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL or Opcodes.ACC_ENUM,
                "RED",
                "Lsample/Color;",
                null,
                null,
            ).visitEnd()
            visitField(
                Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
                "\$VALUES",
                "[Lsample/Color;",
                null,
                null,
            ).visitEnd()
            visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null).apply {
                visitCode()
                visitInsn(Opcodes.RETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
            visitEnd()
        }.toByteArray()

        assertInjected(transform(bytes))
    }

    @Test
    fun `interface`() {
        val bytes = ClassWriter(0).apply {
            visit(Opcodes.V11, Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT, "sample/Contract", null, "java/lang/Object", null)
            visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "run", "()V", null, null).visitEnd()
            visitEnd()
        }.toByteArray()

        assertInjected(transform(bytes))
    }

    @Test
    fun `annotation interface`() {
        val bytes = ClassWriter(0).apply {
            visit(
                Opcodes.V11,
                Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE or Opcodes.ACC_ANNOTATION,
                "sample/Marker",
                null,
                "java/lang/Object",
                arrayOf("java/lang/annotation/Annotation"),
            )
            visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "value", "()Ljava/lang/String;", null, null).visitEnd()
            visitEnd()
        }.toByteArray()

        assertInjected(transform(bytes))
    }

    @Test
    fun `record`() {
        val bytes = ClassWriter(ClassWriter.COMPUTE_FRAMES).apply {
            visit(Opcodes.V16, Opcodes.ACC_PUBLIC or Opcodes.ACC_RECORD or Opcodes.ACC_FINAL, "sample/Point", null, "java/lang/Object", null)
            visitRecordComponent("x", "I", null).visitEnd()
            visitRecordComponent("y", "I", null).visitEnd()
            visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL, "x", "I", null, null).visitEnd()
            visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL, "y", "I", null, null).visitEnd()
            visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(II)V", null, null).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                visitInsn(Opcodes.RETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
            visitEnd()
        }.toByteArray()

        val probe = probe(transform(bytes))
        assertInjected(probe)
        assertEquals(2, probe.fieldAnnotationCount, "record components become fields and must carry the field marker")
        assertEquals(1, probe.methodAnnotationCount)
    }

    @Test
    fun `repeated injection is idempotent`() {
        val once = transform(fixture("sample/Idempotent"))
        val twice = transform(once)

        assertContentEquals(once, twice, "second transform must not add duplicate metadata")
    }

    @Test
    fun `re-injection after marker strip restores the marker`() {
        val injected = transform(fixture("sample/Stripped"))
        val stripped = ClassReader(injected).let { reader ->
            ClassWriter(0).also { writer ->
                reader.accept(
                    object : ClassVisitor(Opcodes.ASM9, writer) {
                        override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? {
                            if (descriptor == AiPolicyMarker.ANNOTATION_DESCRIPTOR) return null
                            return super.visitAnnotation(descriptor, visible)
                        }

                        override fun visitAttribute(attribute: Attribute?) {
                            if (attribute?.type == AiPolicyMarker.ATTRIBUTE_NAME) return
                            super.visitAttribute(attribute)
                        }
                    },
                    0,
                )
            }.toByteArray()
        }
        assertTrue(!hasAiPolicyMarker(stripped), "fixture must start without a marker")

        val reinjected = transform(stripped)
        assertInjected(probe(reinjected))
        assertEquals(1, probe(reinjected).classAnnotationCount, "re-injection must not duplicate")
    }

    @Test
    fun `jar packaged classes carry the marker`() {
        val directory = createTempDirectory("strguard-jar-")
        val jar = directory.resolve("artifact.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            listOf("sample/AppA" to transform(fixture("sample/AppA")), "sample/AppB" to transform(fixture("sample/AppB"))).forEach { (internalName, bytes) ->
                output.putNextEntry(ZipEntry("$internalName.class"))
                output.write(bytes)
                output.closeEntry()
            }
        }

        val result = AiProtectionVerifier.verifyJar(jar)
        assertEquals(2, result.scannedClasses)
        assertEquals(2, result.protectedClasses)
        assertEquals(2, result.verifiedClasses)
        assertEquals(0, result.missingMarkerClasses)
    }

    @Test
    fun `transformed class loads and executes on the JVM`() {
        val loaded = RuntimeHarness().transformAndLoad(fixture("sample/Runnable"), settings)

        assertEquals("fixture-literal", loaded.stringValue("sample/Runnable", "value"))
        assertInjected(probe(loaded.transformedBytes))
    }

    @Test
    fun `strict verification fails when a class misses the marker`() {
        val directory = createTempDirectory("strguard-missing-")
        Files.createDirectories(directory.resolve("sample"))
        Files.write(directory.resolve("sample/Unmarked.class"), fixture("sample/Unmarked"))

        val result = AiProtectionVerifier.verifyDirectory(directory)
        assertEquals(1, result.missingMarkerClasses)

        val failure = assertFailsWith<GradleException> {
            AiProtectionVerifier.requireProtected(result)
        }
        assertTrue(failure.message.orEmpty().contains("AI-NOREV-001"), failure.message.orEmpty())
    }

    @Test
    fun `mixed fields and methods all carry markers`() {
        val probe = probe(transform(fixture("sample/Mixed")))

        assertInjected(probe)
        assertEquals(1, probe.methodAnnotationCount)
        assertEquals(1, probe.fieldAnnotationCount)
        assertEquals(listOf(AiPolicyMarker.MARKER), probe.fieldValues)
    }

    private fun transform(bytes: ByteArray): ByteArray = RuntimeHarness().transformAndLoad(bytes, settings).transformedBytes

    private fun fixture(internalName: String): ByteArray = fixture(internalName) {}

    private fun fixture(internalName: String, customize: (ClassWriter) -> Unit): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        writer.visitField(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
            "CONSTANT",
            "Ljava/lang/String;",
            null,
            "fixture-field-value",
        ).visitEnd()
        customize(writer)
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

    private data class Probe(
        val classAnnotationCount: Int,
        val classAnnotations: Map<String, String>,
        val methodAnnotationCount: Int,
        val fieldAnnotationCount: Int,
        val fieldValues: List<String>,
        val attributeTypes: List<String>,
    )

    private fun assertInjected(bytes: ByteArray) = assertInjected(probe(bytes))

    private fun assertInjected(probe: Probe) {
        assertTrue(probe.classAnnotationCount > 0, "class must carry the policy marker")
        assertTrue(probe.attributeTypes.contains(AiPolicyMarker.ATTRIBUTE_NAME), "attribute must be present")
    }

    private fun probe(bytes: ByteArray): Probe {
        var classAnnotationCount = 0
        val classAnnotations = mutableMapOf<String, String>()
        var methodAnnotationCount = 0
        var fieldAnnotationCount = 0
        val fieldValues = mutableListOf<String>()
        val attributeTypes = mutableListOf<String>()
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? {
                    if (descriptor == AiPolicyMarker.ANNOTATION_DESCRIPTOR) classAnnotationCount += 1
                    return object : AnnotationVisitor(Opcodes.ASM9) {
                        override fun visit(name: String?, value: Any?) {
                            if (descriptor != null && name != null && value is String) {
                                classAnnotations["$descriptor:$name"] = value
                            }
                        }
                    }
                }

                override fun visitAttribute(attribute: Attribute?) {
                    attribute?.type?.let(attributeTypes::add)
                }

                override fun visitMethod(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitAnnotation(annotationDescriptor: String?, visible: Boolean): AnnotationVisitor? {
                            if (annotationDescriptor == AiPolicyMarker.METHOD_ANNOTATION_DESCRIPTOR) {
                                methodAnnotationCount += 1
                            }
                            return null
                        }
                    }
                }

                override fun visitField(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    value: Any?,
                ): FieldVisitor? {
                    return object : FieldVisitor(Opcodes.ASM9) {
                        override fun visitAnnotation(annotationDescriptor: String?, visible: Boolean): AnnotationVisitor? {
                            if (annotationDescriptor != AiPolicyMarker.FIELD_ANNOTATION_DESCRIPTOR) return null
                            fieldAnnotationCount += 1
                            return object : AnnotationVisitor(Opcodes.ASM9) {
                                override fun visit(name: String?, value: Any?) {
                                    if (name == AiPolicyMarker.ELEMENT_VALUE && value is String) fieldValues += value
                                }
                            }
                        }
                    }
                }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        return Probe(classAnnotationCount, classAnnotations, methodAnnotationCount, fieldAnnotationCount, fieldValues, attributeTypes)
    }
}

/** 测试用自定义 attribute(验证注入 pass 保留已有 attribute)。 */
private class ValidationSampleAttribute(private val payload: String) : Attribute("ValidationSampleAttribute") {
    override fun write(
        classWriter: ClassWriter,
        code: ByteArray?,
        len: Int,
        maxStack: Int,
        maxLocals: Int,
    ): ByteVector = ByteVector().putByteArray(payload.toByteArray(), 0, payload.length)
}
