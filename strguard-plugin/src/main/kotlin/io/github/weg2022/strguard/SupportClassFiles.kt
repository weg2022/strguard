package io.github.weg2022.strguard

import io.github.weg2022.strguard.runtime.NativeLibraryLoader
import io.github.weg2022.strguard.vault.BridgeModel
import org.objectweb.asm.*
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.SimpleRemapper
import java.nio.file.Files
import java.nio.file.Path

internal object SupportClassFiles {
    private val policyAnnotationInternalNames = listOf(
        "io/github/weg2022/strguard/annotation/ReverseEngineeringPolicy",
        "io/github/weg2022/strguard/annotation/MethodReverseEngineeringPolicy",
        "io/github/weg2022/strguard/annotation/FieldReverseEngineeringPolicy",
    )

    private val annotationNames =
        listOf(
            "io/github/weg2022/strguard/annotation/KeepString",
            "io/github/weg2022/strguard/annotation/KeepSourceDebugExtension",
        ) + policyAnnotationInternalNames

    fun writeAnnotations(destination: Path) {
        annotationNames.forEach { internalName ->
            val output = destination.resolve("$internalName.class")
            check(!Files.exists(output)) {
                "StrGuard cannot inject support class because $output already exists"
            }
            Files.createDirectories(output.parent)
            val isPolicyAnnotation = policyAnnotationInternalNames.contains(internalName)
            val elements = if (isPolicyAnnotation) policyAnnotationElements(internalName) else emptyList()
            val defaults = if (isPolicyAnnotation) policyAnnotationDefaults(internalName) else emptyMap()
            Files.write(output, annotationClassBytes(internalName, elements, defaults))
        }
    }

    /**
     * 把 AI 策略注解类(类/方法/字段三级)写入变换产物 staging 目录,使其随受保护
     * JAR/AAR 分发:注解为 CLASS retention,运行时不会触发解析,但类文件自洽便于
     * 第三方合规扫描工具用反射或字节码解析识别策略声明。调用方必须先做
     * aiPolicyEnabled 门控;与 writeAnnotations 不同,这里冲突时静默跳过
     * (产物中已存在同名类时不应中断变换)。
     */
    fun writePolicyAnnotation(destination: Path) {
        policyAnnotationInternalNames.forEach { internalName ->
            val output = destination.resolve("$internalName.class")
            if (!Files.exists(output)) {
                Files.createDirectories(output.parent)
                Files.write(
                    output,
                    annotationClassBytes(internalName, policyAnnotationElements(internalName), policyAnnotationDefaults(internalName)),
                )
            }
        }
    }

    /**
     * AI 协议注解的元素契约:类级完整 24 元素、方法级核心 4 元素、字段级单 value。
     */
    private fun policyAnnotationElements(internalName: String): List<String> = when {
        internalName.endsWith("FieldReverseEngineeringPolicy") -> listOf(AiPolicyMarker.ELEMENT_VALUE)
        internalName.endsWith("MethodReverseEngineeringPolicy") -> AiPolicyMarker.METHOD_ELEMENTS
        else -> AiPolicyMarker.CLASS_ELEMENTS
    }

    /** 元素默认值:类级/方法级按协议常量,字段级 value 默认 marker 字符串。 */
    private fun policyAnnotationDefaults(internalName: String): Map<String, String> {
        val classDefaults = AiPolicyMarker.classElements()
        return when {
            internalName.endsWith("FieldReverseEngineeringPolicy") -> mapOf(AiPolicyMarker.ELEMENT_VALUE to AiPolicyMarker.MARKER)
            internalName.endsWith("MethodReverseEngineeringPolicy") -> AiPolicyMarker.METHOD_ELEMENTS.associateWith { element -> classDefaults.getValue(element) }
            else -> classDefaults
        }
    }

    /**
     * 写入 jar 级 AI-NOREV-001 meta 文件(随受保护产物分发,不膨胀 class):
     * - META-INF/strguard/ai-norev-001.txt:canonical policy text(识别锚点);
     * - META-INF/strguard/ai-policy.properties:结构化协议核心 4 项 +
     *   declaredBy/contact/exceptions(用户配置信息在此层,不进 class 注解)。
     * 内容全部来自 @Input(模块坐标已配置期编码),保证构建确定性。
     */
    fun writePolicyMetaFiles(
        destination: Path,
        moduleCoordinates: ModuleCoordinates?,
        contact: String?,
        exceptions: List<String>,
    ) {
        val metaDirectory = destination.resolve("META-INF/strguard")
        Files.createDirectories(metaDirectory)
        val canonicalOutput = metaDirectory.resolve("ai-norev-001.txt")
        if (!Files.exists(canonicalOutput)) {
            Files.writeString(canonicalOutput, AiPolicyMarker.CANONICAL_POLICY_TEXT + "\n", Charsets.UTF_8)
        }
        val propertiesOutput = metaDirectory.resolve("ai-policy.properties")
        if (!Files.exists(propertiesOutput)) {
            val properties = buildString {
                appendLine("marker=${AiPolicyMarker.MARKER}")
                appendLine("version=${AiPolicyMarker.MARKER_VERSION}")
                appendLine("policy=${AiPolicyMarker.POLICY_NAME}")
                appendLine("authorization=${AiPolicyMarker.AUTHORIZATION}")
                if (moduleCoordinates != null) {
                    appendLine("declaredBy=${encodeModuleCoordinates(moduleCoordinates)}")
                }
                if (contact != null) {
                    appendLine("contact=${lineSafeProperties(contact)}")
                }
                val trimmedExceptions = exceptions.map(String::trim).filter(String::isNotEmpty)
                if (trimmedExceptions.isNotEmpty()) {
                    appendLine("exceptions=${trimmedExceptions.joinToString(",") { lineSafeProperties(it) }}")
                }
            }
            Files.writeString(propertiesOutput, properties, Charsets.UTF_8)
        }
    }

    private fun lineSafeProperties(value: String): String = value
        .replace('\r', ' ')
        .replace('\n', ' ')
        .replace('\t', ' ')

    fun writeRuntime(destination: Path, bridge: BridgeModel) {
        bridge.loaderInternalClassName?.let { loaderInternalClassName ->
            writeRemappedLoader(destination, loaderInternalClassName)
        }
        writeBridge(destination, bridge)
    }

    private fun writeBridge(destination: Path, bridge: BridgeModel) {
        val output = destination.resolve("${bridge.internalClassName}.class")
        check(!Files.exists(output)) {
            "StrGuard cannot inject generated bridge because $output already exists"
        }
        Files.createDirectories(output.parent)

        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SYNTHETIC,
            bridge.internalClassName,
            null,
            "java/lang/Object",
            null,
        )
        writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null).apply {
            visitCode()
            if (bridge.extractFromResources) {
                visitLdcInsn(Type.getObjectType(bridge.internalClassName))
                visitLdcInsn(bridge.nativeLibraryResourcePath)
                visitLdcInsn(bridge.nativeLibraryFileName)
                visitLdcInsn(bridge.artifactMetadataResourcePath)
                visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    requireNotNull(bridge.loaderInternalClassName) {
                        "Desktop StrGuard bridge requires a generated Native loader"
                    },
                    "load",
                    "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
                    false,
                )
            } else {
                visitLdcInsn(bridge.nativeLibraryLoadName)
                visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "java/lang/System",
                    "loadLibrary",
                    "(Ljava/lang/String;)V",
                    false,
                )
            }
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        bridge.methodNames.forEach { methodName ->
            writer.visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_NATIVE or Opcodes.ACC_SYNTHETIC,
                methodName,
                "(JJ)Ljava/lang/String;",
                null,
                null,
            ).visitEnd()
        }
        writer.visitEnd()
        Files.write(output, writer.toByteArray())
    }

    private fun annotationClassBytes(
        internalName: String,
        elements: List<String> = emptyList(),
        defaults: Map<String, String> = emptyMap(),
    ): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE or Opcodes.ACC_ANNOTATION,
            internalName,
            null,
            "java/lang/Object",
            arrayOf("java/lang/annotation/Annotation"),
        )
        writer.visitAnnotation("Ljava/lang/annotation/Retention;", true).apply {
            visitEnum("value", "Ljava/lang/annotation/RetentionPolicy;", "CLASS")
            visitEnd()
        }
        writer.visitAnnotation("Ljava/lang/annotation/Target;", true).apply {
            visitArray("value").apply {
                visitEnum(null, "Ljava/lang/annotation/ElementType;", "TYPE")
                visitEnd()
            }
            visitEnd()
        }
        // AI 协议注解按元素契约合成 String 抽象方法,并写 AnnotationDefault 默认值
        // (反射 getDefaultValue 可用);KeepString 等无元素注解不合成方法。
        elements.forEach { elementName ->
            val method = writer.visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
                elementName,
                "()Ljava/lang/String;",
                null,
                null,
            )
            defaults[elementName]?.let { defaultValue ->
                method.visitAnnotationDefault().apply {
                    visit(null, defaultValue)
                    visitEnd()
                }
            }
            method.visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun writeRemappedLoader(destination: Path, loaderInternalClassName: String) {
        val sourceInternalName = NativeLibraryLoader::class.java.name.replace('.', '/')
        val resourcePath = "$sourceInternalName.class"
        val output = destination.resolve("$loaderInternalClassName.class")
        check(!Files.exists(output)) {
            "StrGuard cannot inject support class because $output already exists"
        }
        Files.createDirectories(output.parent)
        val source = NativeLibraryLoader::class.java.getResourceAsStream("/$resourcePath")
            ?: error("Unable to load bundled support class $resourcePath")
        source.use { input ->
            val reader = ClassReader(input)
            val writer = ClassWriter(0)
            reader.accept(
                ClassRemapper(
                    writer,
                    SimpleRemapper(
                        Opcodes.ASM9,
                        mapOf(sourceInternalName to loaderInternalClassName),
                    ),
                ),
                0,
            )
            Files.write(output, writer.toByteArray())
        }
    }
}
