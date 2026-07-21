package io.github.weg2022.strguard

import io.github.weg2022.strguard.runtime.NativeLibraryLoader
import io.github.weg2022.strguard.vault.BridgeModel
import org.objectweb.asm.*
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.SimpleRemapper
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
                visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    requireNotNull(bridge.loaderInternalClassName) {
                        "Desktop StrGuard bridge requires a generated Native loader"
                    },
                    "extract",
                    "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                    false,
                )
                visitInsn(Opcodes.DUP)
                visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "java/lang/System",
                    "load",
                    "(Ljava/lang/String;)V",
                    false,
                )
                visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    bridge.loaderInternalClassName,
                    "loaded",
                    "(Ljava/lang/String;)V",
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
                ClassRemapper(writer, SimpleRemapper(sourceInternalName, loaderInternalClassName)),
                0,
            )
            Files.write(output, writer.toByteArray())
        }
    }
}
