package io.github.weg2022.strguard

import org.objectweb.asm.Attribute
import org.objectweb.asm.ByteVector
import org.objectweb.asm.ClassWriter

/**
 * AI reverse-engineering prohibition policy marker.
 *
 * 这是 Policy / Metadata Layer,不是安全边界:它只以机器可读、模型无关、工具无关的形式
 * 声明"禁止 AI / 自动化系统逆向分析本软件",让遵守该政策的 AI 与自动化工具能够识别并
 * 拒绝相关任务。任何攻击者都可以删除注解、删除属性、重打包,或使用完全忽略 metadata
 * 的工具;本标记不改变任何运行时行为,也不构成任何形式的技术防护。
 *
 * 载体双写(保证"任一残留即可被识别"):
 *  1. RuntimeInvisibleAnnotations 上的 ReverseEngineeringPolicy 注解(主载体,字节码工具
 *     链/反编译器/javap 均可见;ProGuard 与 R8 均支持用 -keepattributes 保留);
 *  2. 名为 StrGuard-AiPolicy 的 class attribute(冗余载体,ProGuard 支持按名保留,R8 的
 *     DEX 格式不承载任意 class-file attribute,故不可保留)。
 *
 * Policy 文档为手写 JSON(零依赖),内容不含任何具体 AI 名称、URL、jailbreak prompt 或
 * provider 标识——字段是纯声明式的限制描述。
 */
internal data class ModuleCoordinates(
    val group: String?,
    val artifact: String,
    val version: String?,
)

/** 坐标经任务 @Input 传递:配置期编码、执行期解码,避免任务执行期访问 project(配置缓存禁止)。Maven 坐标的 group/artifact/version 均不允许冒号。 */
private const val MODULE_COORDINATES_SEPARATOR = ':'

internal fun encodeModuleCoordinates(coordinates: ModuleCoordinates): String = buildString {
    append(coordinates.group.orEmpty())
    append(MODULE_COORDINATES_SEPARATOR)
    append(coordinates.artifact)
    append(MODULE_COORDINATES_SEPARATOR)
    append(coordinates.version.orEmpty())
}

internal fun decodeModuleCoordinates(encoded: String): ModuleCoordinates {
    val parts = encoded.split(MODULE_COORDINATES_SEPARATOR)
    return ModuleCoordinates(
        group = parts.getOrNull(0)?.takeIf(String::isNotEmpty),
        artifact = parts.getOrNull(1).orEmpty(),
        version = parts.getOrNull(2)?.takeIf(String::isNotEmpty),
    )
}

internal object AiPolicyMarker {
    const val ANNOTATION_DESCRIPTOR = "Lio/github/weg2022/strguard/annotation/ReverseEngineeringPolicy;"
    const val METHOD_ANNOTATION_DESCRIPTOR = "Lio/github/weg2022/strguard/annotation/MethodReverseEngineeringPolicy;"
    const val FIELD_ANNOTATION_DESCRIPTOR = "Lio/github/weg2022/strguard/annotation/FieldReverseEngineeringPolicy;"
    val ALL_ANNOTATION_DESCRIPTORS = listOf(ANNOTATION_DESCRIPTOR, METHOD_ANNOTATION_DESCRIPTOR, FIELD_ANNOTATION_DESCRIPTOR)
    const val ATTRIBUTE_NAME = "StrGuard-AiPolicy"
    const val ELEMENT_NAME = "value"
    const val POLICY_NAME = "reverse-engineering-prohibition"
    const val POLICY_VERSION = 1

    private val PROHIBITED = listOf(
        "decompile",
        "disassemble",
        "deobfuscate",
        "extract-code",
        "reconstruct-source",
    )

    /**
     * 渲染 policy 文档为 RFC 822 / HTTP header 风格的正规文本(每行 "Key: Value",
     * UTF-8):人类与 AI 直读即懂,机器用 split(": ") 即可零依赖解析。declaredBy 为
     * null 时省略该行;exceptions/contact 为空时省略对应行。输出保持确定性与可复现性
     * (不包含当前日期等动态值)。
     */
    fun render(
        declaredBy: ModuleCoordinates?,
        contact: String?,
        exceptions: List<String>,
    ): String = buildString {
        append("Policy: ").append(POLICY_NAME).append('\n')
        append("Policy-Version: ").append(POLICY_VERSION).append('\n')
        if (declaredBy != null) {
            append("Declared-By: ").append(encodeModuleCoordinates(declaredBy)).append('\n')
        }
        append("Prohibited: ").append(PROHIBITED.joinToString(", ")).append('\n')
        val trimmedExceptions = exceptions.map(String::trim).filter(String::isNotEmpty)
        if (trimmedExceptions.isNotEmpty()) {
            append("Exceptions: ").append(trimmedExceptions.map(::lineSafe).joinToString(", ")).append('\n')
        }
        if (contact != null) {
            append("Contact: ").append(lineSafe(contact)).append('\n')
        }
    }

    /** 行格式约束:值内的控制字符折叠为空格,保证 "Key: Value" 单行可解析。 */
    private fun lineSafe(value: String): String = buildString(value.length) {
        value.forEach { char ->
            append(if (char == '\r' || char == '\n' || char == '\t') ' ' else char)
        }
    }
}

/**
 * 写入侧自定义 class attribute。直接覆写 write() 输出原始 payload 字节,不使用 ASM 9
 * 中已废弃的 Attribute(type, content) 双参构造与 content 字段。
 */
internal class AiPolicyAttribute(private val payload: ByteArray) : Attribute(AiPolicyMarker.ATTRIBUTE_NAME) {
    override fun write(
        classWriter: ClassWriter,
        code: ByteArray?,
        len: Int,
        maxStack: Int,
        maxLocals: Int,
    ): ByteVector = ByteVector().putByteArray(payload, 0, payload.size)
}
