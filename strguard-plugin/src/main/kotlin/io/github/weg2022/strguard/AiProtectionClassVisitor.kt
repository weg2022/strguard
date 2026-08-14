package io.github.weg2022.strguard

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.Attribute
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * AI-NOREV-001 幂等检测:class 已带类级 marker(类级注解 descriptor 或
 * AI-NOREV-001 attribute)即视为已注入,避免 inject(inject(bytes)) 叠加重复元数据。
 */
internal fun hasAiPolicyMarker(classBytes: ByteArray): Boolean {
    var found = false
    ClassReader(classBytes).accept(
        object : ClassVisitor(Opcodes.ASM9) {
            override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? {
                if (descriptor == AiPolicyMarker.ANNOTATION_DESCRIPTOR) found = true
                return null
            }

            override fun visitAttribute(attribute: Attribute?) {
                if (attribute?.type == AiPolicyMarker.ATTRIBUTE_NAME) found = true
            }
        },
        ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
    )
    return found
}

/**
 * AI-NOREV-001 独立注入 pass:只添加 policy marker 元数据——类级完整 24 元素注解
 * + AI-NOREV-001 attribute、每方法核心 4 元素注解、每字段单 value(marker)注解。
 * 不修改 methods/fields/control flow,保留全部已有 metadata。
 *
 * 与字符串混淆 pass 分离(ClassReader → 本 visitor → FramesComputingClassWriter),
 * 注入的注解不经过 StringObfuscationClassVisitor 的 trackedAnnotation,天然规避
 * strictStringCoverage 把 policy 字符串计为应用字符串的误报。
 */
internal class AiProtectionClassVisitor(
    private val classElements: Map<String, String>,
    delegate: ClassWriter,
) : ClassVisitor(Opcodes.ASM9, delegate) {
    override fun visitField(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        value: Any?,
    ): FieldVisitor? {
        val fieldVisitor = super.visitField(access, name, descriptor, signature, value)
        if (fieldVisitor != null) {
            // 字段级:单 value = marker 字符串(FieldVisitor.visitAnnotation 先于字段其它属性)。
            val annotation = fieldVisitor.visitAnnotation(AiPolicyMarker.FIELD_ANNOTATION_DESCRIPTOR, false)
            if (annotation != null) {
                annotation.visit(AiPolicyMarker.ELEMENT_VALUE, AiPolicyMarker.MARKER)
                annotation.visitEnd()
            }
        }
        return fieldVisitor
    }

    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor? {
        val methodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions)
        if (methodVisitor != null) {
            // 方法级:核心 4 元素,先于方法体其它内容(ClassWriter 要求注解先于 visitCode)。
            val annotation = methodVisitor.visitAnnotation(AiPolicyMarker.METHOD_ANNOTATION_DESCRIPTOR, false)
            if (annotation != null) {
                AiPolicyMarker.METHOD_ELEMENTS.forEach { element ->
                    annotation.visit(element, classElements.getValue(element))
                }
                annotation.visitEnd()
            }
        }
        return methodVisitor
    }

    override fun visitEnd() {
        // 类级:完整 24 元素注解 + AI-NOREV-001 attribute,先于 super.visitEnd 发射。
        val annotation = super.visitAnnotation(AiPolicyMarker.ANNOTATION_DESCRIPTOR, false)
        if (annotation != null) {
            AiPolicyMarker.CLASS_ELEMENTS.forEach { element ->
                annotation.visit(element, classElements.getValue(element))
            }
            annotation.visitEnd()
        }
        super.visitAttribute(AiPolicyAttribute(AiPolicyMarker.compactAttributeText().toByteArray(Charsets.UTF_8)))
        super.visitEnd()
    }
}
