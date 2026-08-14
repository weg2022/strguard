package io.github.weg2022.strguard

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.Attribute
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiPolicyMarkerTest {
    @Test
    fun `enabled marker writes class and method markers plus a field marker carrying the policy text`() {
        val fixture = RuntimeHarness().transformAndLoad(
            policyFixture("sample/PolicyFixture"),
            settings(
                aiPolicyEnabled = true,
                aiPolicyContact = "legal@example.com",
                aiPolicyExceptions = listOf("authorized security research"),
                moduleCoordinates = ModuleCoordinates("com.example", "app", "1.2.3"),
                strictStringCoverage = true,
            ),
        )
        val transformed = fixture.transformedBytes

        val probe = probe(transformed)
        assertTrue(probe.classAnnotationPresent, "class must carry the policy marker annotation")
        assertFalse(probe.classAnnotationVisible!!, "class marker must be runtime-invisible")
        assertNull(probe.classAnnotationValue, "class marker is a bare marker without a value element")
        assertTrue(probe.methodAnnotationPresent, "every method must carry the policy marker annotation")
        assertTrue(probe.fieldAnnotationPresent, "every field must carry the policy marker annotation")
        val value = probe.fieldAnnotationValue ?: error("field marker annotation must carry the policy text")
        assertTrue(value.startsWith("Policy: reverse-engineering-prohibition\n"), value)
        assertTrue(value.contains("Policy-Version: 1\n"), value)
        assertTrue(value.contains("Declared-By: com.example:app:1.2.3\n"), value)
        assertTrue(
            value.contains("Prohibited: decompile, disassemble, deobfuscate, extract-code, reconstruct-source\n"),
            value,
        )
        assertTrue(value.contains("Exceptions: authorized security research\n"), value)
        assertTrue(value.contains("Contact: legal@example.com\n"), value)
        assertFalse(value.contains('{'), "policy must be plain text, not JSON")
        assertTrue(probe.attributePresent, "redundant StrGuard-AiPolicy attribute must be present")
        assertTrue(
            transformed.toString(StandardCharsets.ISO_8859_1).contains(AiPolicyMarker.ATTRIBUTE_NAME),
            "attribute name must appear in class bytes",
        )
        assertEquals(
            0L,
            fixture.coverage.skipped(StringSkipReason.ANNOTATION_STRING),
            "injected policy text must never be counted as an application string",
        )
    }

    @Test
    fun `disabled marker injects nothing`() {
        val transformed = transform(policyFixture("sample/PolicyFixture"), settings(aiPolicyEnabled = false))

        val probe = probe(transformed)
        assertFalse(probe.classAnnotationPresent)
        assertFalse(probe.methodAnnotationPresent)
        assertFalse(probe.fieldAnnotationPresent)
        assertFalse(probe.attributePresent)
    }

    @Test
    fun `package selection filters which classes receive the marker`() {
        val selected = transform(policyFixture("sample/Selected"), settings(aiPolicyEnabled = true, aiPolicyPackages = listOf("sample")))
        val excluded = transform(policyFixture("other/Excluded"), settings(aiPolicyEnabled = true, aiPolicyPackages = listOf("sample")))

        val selectedProbe = probe(selected)
        assertTrue(selectedProbe.classAnnotationPresent)
        assertTrue(selectedProbe.methodAnnotationPresent)
        assertTrue(selectedProbe.fieldAnnotationPresent)
        val excludedProbe = probe(excluded)
        assertFalse(excludedProbe.classAnnotationPresent)
        assertFalse(excludedProbe.methodAnnotationPresent)
        assertFalse(excludedProbe.fieldAnnotationPresent)
        assertFalse(excludedProbe.attributePresent)
    }

    @Test
    fun `marker is orthogonal to string protection`() {
        val transformed = transform(
            policyFixture("sample/PolicyFixture"),
            settings(aiPolicyEnabled = true, stringGuardPackages = listOf("other.pkg")),
        )

        val probe = probe(transformed)
        assertTrue(probe.classAnnotationPresent, "marker must be injected even when string protection does not select the class")
        assertTrue(probe.methodAnnotationPresent)
        assertTrue(probe.fieldAnnotationPresent)
        assertTrue(
            transformed.toString(StandardCharsets.ISO_8859_1).contains("fixture-literal"),
            "unselected string literals must stay untouched",
        )
        assertTrue(probe.attributePresent)
    }

    @Test
    fun `support classes never receive the marker`() {
        val transformed = transform(
            policyFixture("io/github/weg2022/strguard/generated/Internal"),
            settings(aiPolicyEnabled = true),
        )

        val probe = probe(transformed)
        assertFalse(probe.classAnnotationPresent)
        assertFalse(probe.methodAnnotationPresent)
        assertFalse(probe.fieldAnnotationPresent)
        assertFalse(probe.attributePresent)
    }

    @Test
    fun `user-supplied contact is made line safe and quotes stay literal in plain text`() {
        val contact = "legal \"quoted\" team \nline2"
        val rendered = AiPolicyMarker.render(
            declaredBy = null,
            contact = contact,
            exceptions = emptyList(),
        )

        assertTrue(rendered.contains("Contact: legal \"quoted\" team  line2\n"), rendered)
        assertFalse(rendered.contains("\nDeclared-By"), "absent declaredBy must be omitted")
        assertFalse(rendered.contains("Exceptions:"), "empty exceptions must be omitted")
        assertFalse(rendered.contains('\\'), "plain text must not carry JSON-style escapes")
    }

    @Test
    fun `render uses the declared-by encoding for module coordinates`() {
        val withAll = AiPolicyMarker.render(
            declaredBy = ModuleCoordinates("com.example", "app", "1.0.0"),
            contact = null,
            exceptions = emptyList(),
        )
        assertTrue(withAll.contains("Declared-By: com.example:app:1.0.0\n"), withAll)

        val sparse = AiPolicyMarker.render(
            declaredBy = ModuleCoordinates(null, "app", null),
            contact = null,
            exceptions = emptyList(),
        )
        assertTrue(sparse.contains("Declared-By: :app:\n"), sparse)
    }

    @Test
    fun `shipped shrinker rules keep the marker annotations and their classes`() {
        val rules = StrGuardShrinkerRules.text

        assertTrue(rules.contains("-keepattributes RuntimeInvisibleAnnotations"), rules)
        listOf(
            "io.github.weg2022.strguard.annotation.ReverseEngineeringPolicy",
            "io.github.weg2022.strguard.annotation.MethodReverseEngineeringPolicy",
            "io.github.weg2022.strguard.annotation.FieldReverseEngineeringPolicy",
        ).forEach { annotationClass ->
            assertTrue(
                rules.contains("-keep class $annotationClass { *; }"),
                "rules must keep annotation class $annotationClass\n$rules",
            )
        }
    }

    @Test
    fun `support annotation classes have the right shapes`() {
        val destination = Files.createTempDirectory("strguard-annotations-")
        SupportClassFiles.writePolicyAnnotation(destination)

        val classAnnotation = destination.resolve("io/github/weg2022/strguard/annotation/ReverseEngineeringPolicy.class")
        val methodAnnotation = destination.resolve("io/github/weg2022/strguard/annotation/MethodReverseEngineeringPolicy.class")
        val fieldAnnotation = destination.resolve("io/github/weg2022/strguard/annotation/FieldReverseEngineeringPolicy.class")
        assertTrue(Files.exists(classAnnotation), "class-level annotation class must be generated")
        assertTrue(Files.exists(methodAnnotation), "method-level annotation class must be generated")
        assertTrue(Files.exists(fieldAnnotation), "field-level annotation class must be generated")

        assertEquals(emptyList(), memberNames(Files.readAllBytes(classAnnotation)), "class-level annotation has no elements")
        assertEquals(emptyList(), memberNames(Files.readAllBytes(methodAnnotation)), "method-level annotation has no elements")
        assertEquals(listOf("value"), memberNames(Files.readAllBytes(fieldAnnotation)), "field-level annotation has a value element")
    }

    private fun memberNames(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    name?.let(names::add)
                    return null
                }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        return names
    }

    @Test
    fun `module coordinates round-trip through the task input encoding`() {
        val full = ModuleCoordinates("com.example", "app", "1.0.0")
        assertEquals(full, decodeModuleCoordinates(encodeModuleCoordinates(full)))

        val sparse = ModuleCoordinates(null, "app", null)
        assertEquals(sparse, decodeModuleCoordinates(encodeModuleCoordinates(sparse)))
        assertFalse(decodeModuleCoordinates("").artifact.isNotEmpty())
    }

    private data class Probe(
        val classAnnotationPresent: Boolean,
        val classAnnotationVisible: Boolean?,
        val classAnnotationValue: String?,
        val methodAnnotationPresent: Boolean,
        val fieldAnnotationPresent: Boolean,
        val fieldAnnotationValue: String?,
        val attributePresent: Boolean,
    )

    private fun probe(bytes: ByteArray): Probe {
        var classAnnotationPresent = false
        var classAnnotationVisible: Boolean? = null
        var classAnnotationValue: String? = null
        var methodAnnotationPresent = false
        var fieldAnnotationPresent = false
        var fieldAnnotationValue: String? = null
        var attributePresent = false
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? {
                    if (descriptor == AiPolicyMarker.ANNOTATION_DESCRIPTOR) {
                        classAnnotationPresent = true
                        classAnnotationVisible = visible
                        return policyValueVisitor { value -> classAnnotationValue = value }
                    }
                    return null
                }

                override fun visitMethod(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    // 不委托 super:ClassVisitor 默认返回 null,委托会丢失所有方法级回调。
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitAnnotation(annotationDescriptor: String?, visible: Boolean): AnnotationVisitor? {
                            if (annotationDescriptor == AiPolicyMarker.METHOD_ANNOTATION_DESCRIPTOR) {
                                methodAnnotationPresent = true
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
                    // 不委托 super:ClassVisitor 默认返回 null,委托会丢失所有字段级回调。
                    return object : FieldVisitor(Opcodes.ASM9) {
                        override fun visitAnnotation(annotationDescriptor: String?, visible: Boolean): AnnotationVisitor? {
                            if (annotationDescriptor == AiPolicyMarker.FIELD_ANNOTATION_DESCRIPTOR) {
                                fieldAnnotationPresent = true
                                return policyValueVisitor { value -> fieldAnnotationValue = value }
                            }
                            return null
                        }
                    }
                }

                override fun visitAttribute(attribute: Attribute?) {
                    if (attribute?.type == AiPolicyMarker.ATTRIBUTE_NAME) attributePresent = true
                }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        return Probe(
            classAnnotationPresent,
            classAnnotationVisible,
            classAnnotationValue,
            methodAnnotationPresent,
            fieldAnnotationPresent,
            fieldAnnotationValue,
            attributePresent,
        )
    }

    private fun policyValueVisitor(consume: (String) -> Unit): AnnotationVisitor = object : AnnotationVisitor(Opcodes.ASM9) {
        override fun visit(name: String?, value: Any?) {
            if (name == AiPolicyMarker.ELEMENT_NAME && value is String) consume(value)
        }
    }

    private fun transform(original: ByteArray, settings: TransformSettings): ByteArray = RuntimeHarness().transformAndLoad(original, settings).transformedBytes

    private fun policyFixture(internalName: String): ByteArray {
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

    private fun settings(
        aiPolicyEnabled: Boolean,
        aiPolicyContact: String? = null,
        aiPolicyExceptions: List<String> = emptyList(),
        aiPolicyPackages: List<String> = emptyList(),
        stringGuardPackages: List<String> = listOf("sample"),
        moduleCoordinates: ModuleCoordinates? = null,
        strictStringCoverage: Boolean = false,
    ): TransformSettings = TransformSettings(
        enabled = true,
        java9StringConcatEnabled = true,
        strictStringCoverage = strictStringCoverage,
        removeSourceDebugExtension = false,
        stringGuardPackages = stringGuardPackages,
        keepStringPackages = emptyList(),
        removeSourceDebugExtensionPackages = emptyList(),
        keepSourceDebugExtensionPackages = emptyList(),
        aiPolicyEnabled = aiPolicyEnabled,
        aiPolicyContact = aiPolicyContact,
        aiPolicyExceptions = aiPolicyExceptions,
        aiPolicyPackages = aiPolicyPackages,
        moduleCoordinates = moduleCoordinates,
    )
}
