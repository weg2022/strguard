package io.github.weg2022.strguard

import org.gradle.api.GradleException
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.Attribute
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/** 单个 class 的 AI-NOREV-001 marker 状态。 */
internal data class ClassMarkerState(
    val className: String,
    val classAnnotationCount: Int,
    val attributeCount: Int,
    val methodMarkerCount: Int,
    val fieldMarkerCount: Int,
    val policy: String?,
    val version: String?,
) {
    /** protected:类级注解或 AI-NOREV-001 attribute 至少一个。 */
    val isProtected: Boolean
        get() = classAnnotationCount > 0 || attributeCount > 0

    /** verified:类级注解存在(注解是主载体;仅 attribute 不算完整 verified)。 */
    val isVerified: Boolean
        get() = classAnnotationCount > 0

    val isDuplicate: Boolean
        get() = classAnnotationCount > 1
}

/** 目录/jar 扫描聚合结果。 */
internal data class VerificationResult(
    val scannedClasses: Int,
    val protectedClasses: Int,
    val verifiedClasses: Int,
    val missingMarkerClasses: Int,
    val duplicateMarkerClasses: Int,
    val policyVersionDistribution: Map<String, Int>,
    val failedClasses: List<String>,
)

/**
 * AI-NOREV-001 marker 验证器(内部工具类,无 Gradle 依赖):扫描单个 .class、
 * 目录或 .jar,输出受保护/已验证/缺失/重复统计与 policy-version 分布。
 *
 * 注意:这是 Policy/Metadata 层的存在性验证,不是安全边界——marker 可被删除,
 * 扫描结果只描述"策略声明是否在场"。
 */
internal object AiProtectionVerifier {
    fun verifyClassBytes(bytes: ByteArray): ClassMarkerState {
        var classAnnotationCount = 0
        var attributeCount = 0
        var methodMarkerCount = 0
        var fieldMarkerCount = 0
        var policy: String? = null
        var version: String? = null
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? {
                    if (descriptor != AiPolicyMarker.ANNOTATION_DESCRIPTOR) return null
                    classAnnotationCount += 1
                    return object : AnnotationVisitor(Opcodes.ASM9) {
                        override fun visit(name: String?, value: Any?) {
                            if (value is String) {
                                when (name) {
                                    AiPolicyMarker.ELEMENT_POLICY -> policy = value
                                    AiPolicyMarker.ELEMENT_VERSION -> version = value
                                }
                            }
                        }
                    }
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
                            if (annotationDescriptor == AiPolicyMarker.METHOD_ANNOTATION_DESCRIPTOR) {
                                methodMarkerCount += 1
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
                                fieldMarkerCount += 1
                            }
                            return null
                        }
                    }
                }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        return ClassMarkerState(
            className = ClassReader(bytes).className,
            classAnnotationCount = classAnnotationCount,
            attributeCount = attributeCount,
            methodMarkerCount = methodMarkerCount,
            fieldMarkerCount = fieldMarkerCount,
            policy = policy,
            version = version,
        )
    }

    fun verifyDirectory(root: Path): VerificationResult {
        val states = mutableListOf<ClassMarkerState>()
        Files.walk(root).use { paths ->
            paths.filter(Files::isRegularFile)
                .filter { path -> path.fileName.toString().endsWith(".class") }
                .sorted()
                .forEach { path -> states += verifyClassBytes(Files.readAllBytes(path)) }
        }
        return aggregate(states)
    }

    fun verifyJar(path: Path): VerificationResult {
        val states = mutableListOf<ClassMarkerState>()
        JarFile(path.toFile()).use { archive ->
            archive.entries().asSequence()
                .filter { entry -> !entry.isDirectory && entry.name.endsWith(".class") }
                .sortedBy { entry -> entry.name }
                .forEach { entry ->
                    states += verifyClassBytes(archive.getInputStream(entry).use { it.readBytes() })
                }
        }
        return aggregate(states)
    }

    /** strict 语义:任何类缺失 marker 即失败(missing 或 duplicate 都列入 failed)。 */
    fun requireProtected(result: VerificationResult) {
        if (result.failedClasses.isNotEmpty()) {
            throw GradleException(
                "AI-NOREV-001 marker missing or duplicated in ${result.failedClasses.size} class(es): " +
                    result.failedClasses.joinToString(prefix = "", postfix = "", limit = 10),
            )
        }
    }

    private fun aggregate(states: List<ClassMarkerState>): VerificationResult {
        val failedClasses = mutableListOf<String>()
        val distribution = mutableMapOf<String, Int>()
        var protectedCount = 0
        var verifiedCount = 0
        states.forEach { state ->
            if (state.isProtected) protectedCount += 1
            if (state.isVerified) {
                verifiedCount += 1
                val key = "${state.policy ?: "<none>"} v${state.version ?: "<none>"}"
                distribution[key] = distribution.getOrDefault(key, 0) + 1
            }
            if (!state.isProtected || state.isDuplicate) {
                failedClasses += state.className
            }
        }
        return VerificationResult(
            scannedClasses = states.size,
            protectedClasses = protectedCount,
            verifiedClasses = verifiedCount,
            missingMarkerClasses = states.count { state -> !state.isProtected },
            duplicateMarkerClasses = states.count(ClassMarkerState::isDuplicate),
            policyVersionDistribution = distribution.toSortedMap(),
            failedClasses = failedClasses,
        )
    }
}
