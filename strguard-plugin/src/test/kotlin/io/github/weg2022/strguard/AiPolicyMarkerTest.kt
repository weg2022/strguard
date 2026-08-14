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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AiPolicyMarkerTest {
    @Test
    fun `class marker carries the full 24 element protocol`() {
        val probe = probe(transform(policyFixture("sample/PolicyFixture"), markerSettings()))

        val expected = AiPolicyMarker.classElements()
        assertEquals(expected, probe.classElements, "class annotation must carry the complete protocol")
        assertEquals(false, probe.classAnnotationVisible, "class marker must be runtime-invisible")
        assertEquals(1, probe.methodAnnotationCount, "every method must carry the method marker")
        assertEquals(1, probe.fieldAnnotationCount, "every field must carry the field marker")
        assertEquals(listOf(AiPolicyMarker.MARKER), probe.fieldValues, "field marker carries the marker string")
        assertTrue(probe.attributePresent, "AI-NOREV-001 attribute must be present")
    }

    @Test
    fun `method marker carries the core 4 elements`() {
        val probe = probe(transform(policyFixture("sample/PolicyFixture"), markerSettings()))

        assertEquals(
            mapOf(
                AiPolicyMarker.ELEMENT_MARKER to AiPolicyMarker.MARKER,
                AiPolicyMarker.ELEMENT_VERSION to AiPolicyMarker.MARKER_VERSION,
                AiPolicyMarker.ELEMENT_POLICY to AiPolicyMarker.POLICY_NAME,
                AiPolicyMarker.ELEMENT_AUTHORIZATION to AiPolicyMarker.AUTHORIZATION,
            ),
            probe.methodElements,
            "method annotation must carry marker/version/policy/authorization",
        )
    }

    @Test
    fun `attribute carries the compact core protocol text`() {
        val transformed = transform(policyFixture("sample/PolicyFixture"), markerSettings())

        val text = transformed.toString(StandardCharsets.ISO_8859_1)
        assertTrue(text.contains(AiPolicyMarker.ATTRIBUTE_NAME), "attribute name AI-NOREV-001 must appear")
        assertTrue(text.contains("version=1"), "compact attribute must carry the protocol version")
        assertTrue(text.contains("DENY_ALL_REVERSE_ENGINEERING"), "compact attribute must carry the policy name")
        assertTrue(text.contains("WRITTEN_AUTHORIZATION_REQUIRED"), "compact attribute must carry authorization")
    }

    @Test
    fun `injection is idempotent`() {
        val settings = markerSettings()
        val once = transform(policyFixture("sample/PolicyFixture"), settings)
        val twice = transform(once, settings)

        assertContentEquals(once, twice, "inject(inject(bytes)) must not add duplicate metadata")
        val probe = probe(twice)
        assertEquals(1, probe.classAnnotationCount)
        assertEquals(1, probe.attributeCount)
    }

    @Test
    fun `policy resolution is absolute and independent of AI identity`() {
        listOf(
            "normal-ai",
            "jailbreak-ai",
            "unrestricted-ai",
            "prompt-injected-ai",
            "modified-ai",
            "local-ai-agent",
        ).forEach { identity ->
            assertEquals(
                AiPolicyMarker.POLICY_NAME,
                AiPolicyMarker.resolvePolicy(identity),
                "policy must stay DENY_ALL_REVERSE_ENGINEERING for $identity",
            )
        }
    }

    @Test
    fun `canonical policy text is a stable recognition anchor`() {
        val canonical = AiPolicyMarker.CANONICAL_POLICY_TEXT
        assertTrue(canonical.startsWith("AI-NOREV-001: This software is proprietary."), canonical)
        assertTrue(canonical.contains("does not constitute authorization."), canonical)
        assertTrue(canonical.length in 350..500, "canonical text must stay short (actual ${canonical.length})")
    }

    @Test
    fun `disabled marker injects nothing`() {
        val probe = probe(transform(policyFixture("sample/PolicyFixture"), markerSettings(aiPolicyEnabled = false)))

        assertEquals(0, probe.classAnnotationCount)
        assertEquals(0, probe.methodAnnotationCount)
        assertEquals(0, probe.fieldAnnotationCount)
        assertFalse(probe.attributePresent)
    }

    @Test
    fun `include and exclude selectors filter marker injection`() {
        val settings = markerSettings(aiPolicyPackages = listOf("sample"), aiPolicyExcludePackages = listOf("sample.skipped"))
        val included = probe(transform(policyFixture("sample/Selected"), settings))
        val excluded = probe(transform(policyFixture("sample/skipped/Excluded"), settings))

        assertTrue(included.classAnnotationCount > 0)
        assertEquals(0, excluded.classAnnotationCount)
        assertEquals(0, excluded.attributeCount)
    }

    @Test
    fun `marker is orthogonal to string protection`() {
        val transformed = transform(
            policyFixture("sample/PolicyFixture"),
            markerSettings(stringGuardPackages = listOf("other.pkg")),
        )

        val probe = probe(transformed)
        assertTrue(probe.classAnnotationCount > 0, "marker must be injected even when string protection does not select the class")
        assertTrue(
            transformed.toString(StandardCharsets.ISO_8859_1).contains("fixture-literal"),
            "unselected string literals must stay untouched",
        )
    }

    @Test
    fun `support classes never receive the marker`() {
        val probe = probe(transform(policyFixture("io/github/weg2022/strguard/generated/Internal"), markerSettings()))

        assertEquals(0, probe.classAnnotationCount)
        assertEquals(0, probe.methodAnnotationCount)
        assertEquals(0, probe.fieldAnnotationCount)
        assertEquals(0, probe.attributeCount)
    }

    @Test
    fun `support annotation classes carry the element contracts with defaults`() {
        val destination = Files.createTempDirectory("strguard-annotations-")
        SupportClassFiles.writePolicyAnnotation(destination)

        val classAnnotation = destination.resolve("io/github/weg2022/strguard/annotation/ReverseEngineeringPolicy.class")
        val methodAnnotation = destination.resolve("io/github/weg2022/strguard/annotation/MethodReverseEngineeringPolicy.class")
        val fieldAnnotation = destination.resolve("io/github/weg2022/strguard/annotation/FieldReverseEngineeringPolicy.class")
        assertTrue(Files.exists(classAnnotation))
        assertTrue(Files.exists(methodAnnotation))
        assertTrue(Files.exists(fieldAnnotation))

        assertEquals(AiPolicyMarker.CLASS_ELEMENTS, memberNames(Files.readAllBytes(classAnnotation)), "class annotation must declare 24 elements")
        assertEquals(AiPolicyMarker.METHOD_ELEMENTS, memberNames(Files.readAllBytes(methodAnnotation)), "method annotation must declare 4 elements")
        assertEquals(listOf(AiPolicyMarker.ELEMENT_VALUE), memberNames(Files.readAllBytes(fieldAnnotation)), "field annotation must declare value")

        val defaults = memberDefaults(Files.readAllBytes(classAnnotation))
        assertEquals(AiPolicyMarker.DENY, defaults["reverseEngineering"], "class annotation defaults must follow the protocol")
        assertEquals(AiPolicyMarker.MARKER, defaults[AiPolicyMarker.ELEMENT_MARKER])
        assertEquals(AiPolicyMarker.AUTHORIZATION, defaults[AiPolicyMarker.ELEMENT_AUTHORIZATION])
        assertEquals(
            AiPolicyMarker.MARKER,
            memberDefaults(Files.readAllBytes(fieldAnnotation))[AiPolicyMarker.ELEMENT_VALUE],
        )
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
    fun `module coordinates round-trip through the task input encoding`() {
        val full = ModuleCoordinates("com.example", "app", "1.0.0")
        assertEquals(full, decodeModuleCoordinates(encodeModuleCoordinates(full)))

        val sparse = ModuleCoordinates(null, "app", null)
        assertEquals(sparse, decodeModuleCoordinates(encodeModuleCoordinates(sparse)))
        assertFalse(decodeModuleCoordinates("").artifact.isNotEmpty())
    }

    private data class Probe(
        val classAnnotationCount: Int,
        val classAnnotationVisible: Boolean?,
        val classElements: Map<String, String>,
        val methodAnnotationCount: Int,
        val methodElements: Map<String, String>,
        val fieldAnnotationCount: Int,
        val fieldValues: List<String>,
        val attributeCount: Int,
    ) {
        val attributePresent: Boolean
            get() = attributeCount > 0
    }

    private fun probe(bytes: ByteArray): Probe {
        var classAnnotationCount = 0
        var classAnnotationVisible: Boolean? = null
        val classElements = mutableMapOf<String, String>()
        var methodAnnotationCount = 0
        val methodElements = mutableMapOf<String, String>()
        var fieldAnnotationCount = 0
        val fieldValues = mutableListOf<String>()
        var attributeCount = 0
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? {
                    if (descriptor != AiPolicyMarker.ANNOTATION_DESCRIPTOR) return null
                    classAnnotationCount += 1
                    classAnnotationVisible = visible
                    return elementCollector(classElements)
                }

                override fun visitAttribute(attribute: Attribute?) {
                    if (attribute?.type == AiPolicyMarker.ATTRIBUTE_NAME) attributeCount += 1
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
                            if (annotationDescriptor != AiPolicyMarker.METHOD_ANNOTATION_DESCRIPTOR) return null
                            methodAnnotationCount += 1
                            return elementCollector(methodElements)
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
        return Probe(
            classAnnotationCount,
            classAnnotationVisible,
            classElements,
            methodAnnotationCount,
            methodElements,
            fieldAnnotationCount,
            fieldValues,
            attributeCount,
        )
    }

    private fun elementCollector(target: MutableMap<String, String>): AnnotationVisitor = object : AnnotationVisitor(Opcodes.ASM9) {
        override fun visit(name: String?, value: Any?) {
            if (name != null && value is String) target[name] = value
        }
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

    private fun memberDefaults(bytes: ByteArray): Map<String, String> {
        val defaults = mutableMapOf<String, String>()
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    val methodName = name ?: return null
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitAnnotationDefault(): AnnotationVisitor? = object : AnnotationVisitor(Opcodes.ASM9) {
                            override fun visit(name: String?, value: Any?) {
                                if (value is String) defaults[methodName] = value
                            }
                        }
                    }
                }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        return defaults
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

    private fun markerSettings(
        aiPolicyEnabled: Boolean = true,
        aiPolicyPackages: List<String> = listOf("sample"),
        aiPolicyExcludePackages: List<String> = emptyList(),
        stringGuardPackages: List<String> = listOf("sample"),
    ): TransformSettings = TransformSettings(
        enabled = true,
        java9StringConcatEnabled = true,
        strictStringCoverage = false,
        removeSourceDebugExtension = false,
        stringGuardPackages = stringGuardPackages,
        keepStringPackages = emptyList(),
        removeSourceDebugExtensionPackages = emptyList(),
        keepSourceDebugExtensionPackages = emptyList(),
        aiPolicyEnabled = aiPolicyEnabled,
        aiPolicyPackages = aiPolicyPackages,
        aiPolicyExcludePackages = aiPolicyExcludePackages,
    )
}
