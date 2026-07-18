package io.github.weg2022.strguard

import io.github.weg2022.strguard.runtime.NativeLibraryLoader
import io.github.weg2022.strguard.vault.BridgeModel
import org.objectweb.asm.*
import java.nio.file.Files
import java.nio.file.Path

internal object SupportClassFiles {
    private val annotationNames = listOf(
        "io/github/weg2022/strguard/annotation/KeepString",
        "io/github/weg2022/strguard/annotation/KeepMetadata",
    )

    fun writeAnnotations(destination: Path) {
        annotationNames.forEach { internalName ->
            val output = destination.resolve("$internalName.class")
            check(!Files.exists(output)) {
                "StrGuard cannot inject support class because $output already exists"
            }
            Files.createDirectories(output.parent)
            Files.write(output, annotationClassBytes(internalName))
        }
    }

    fun writeRuntimeAndAnnotations(destination: Path, bridge: BridgeModel) {
        writeAnnotations(destination)
        copyClassFile(NativeLibraryLoader::class.java, destination)
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
            visitLdcInsn(Type.getObjectType(bridge.internalClassName))
            visitLdcInsn(bridge.nativeLibraryResourcePath)
            visitLdcInsn(bridge.nativeLibraryFileName)
            visitMethodInsn(
                Opcodes.INVOKESTATIC,
                NATIVE_LOADER_INTERNAL_NAME,
                "extract",
                "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                false,
            )
            visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/lang/System",
                "load",
                "(Ljava/lang/String;)V",
                false,
            )
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

    private fun annotationClassBytes(internalName: String): ByteArray {
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
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun copyClassFile(type: Class<*>, destination: Path) {
        val resourcePath = type.name.replace('.', '/') + ".class"
        val output = destination.resolve(resourcePath)
        check(!Files.exists(output)) {
            "StrGuard cannot inject support class because $output already exists"
        }
        Files.createDirectories(output.parent)
        val source = type.getResourceAsStream("/$resourcePath")
            ?: error("Unable to load bundled support class $resourcePath")
        source.use { input ->
            Files.newOutputStream(output).use(input::copyTo)
        }
    }
}

private const val NATIVE_LOADER_INTERNAL_NAME =
    "io/github/weg2022/strguard/runtime/NativeLibraryLoader"
