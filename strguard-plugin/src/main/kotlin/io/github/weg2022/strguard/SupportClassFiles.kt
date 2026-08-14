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
            val elements = policyAnnotationElements(internalName)
            Files.write(output, annotationClassBytes(internalName, elements))
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
                Files.write(output, annotationClassBytes(internalName, policyAnnotationElements(internalName)))
            }
        }
    }

    /**
     * 只有字段级注解携带 value 元素(policy 文本);类级与方法级是无值标记。
     */
    private fun policyAnnotationElements(internalName: String): List<String> = if (internalName.endsWith("FieldReverseEngineeringPolicy")) listOf(AiPolicyMarker.ELEMENT_NAME) else emptyList()

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

    private fun annotationClassBytes(internalName: String, elements: List<String> = emptyList()): ByteArray {
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
        // ReverseEngineeringPolicy 的 value 元素是注入的 policy JSON 文档;KeepString 等
        // 无元素注解不合成方法。
        elements.forEach { elementName ->
            writer.visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
                elementName,
                "()Ljava/lang/String;",
                null,
                null,
            ).visitEnd()
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
