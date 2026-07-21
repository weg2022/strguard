package io.github.weg2022.strguard

import io.github.weg2022.strguard.vault.SecureVaultBuilder
import io.github.weg2022.strguard.vault.VaultReference
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.LocalVariablesSorter

internal data class ClassTransformResult(
    val bytes: ByteArray,
    val metadataMappings: Set<String>,
)

internal object ClassTransformer {
    fun transform(
        classBytes: ByteArray,
        settings: TransformSettings,
        vaultBuilder: SecureVaultBuilder,
    ): ClassTransformResult {
        val classReader = ClassReader(classBytes)
        val exclusions = ClassExclusions.scan(classBytes)
        val className = classReader.className
        val visitor =
            StringObfuscationClassVisitor(
                settings = settings,
                processStrings = settings.shouldTransformStrings(className) && !exclusions.keepStrings,
                processMetadata = settings.shouldRemoveMetadata(className) && !exclusions.keepMetadata,
                vaultBuilder = vaultBuilder,
                delegate = MaxsComputingClassWriter(),
            )
        classReader.accept(visitor, ClassReader.EXPAND_FRAMES)
        return ClassTransformResult(
            bytes = visitor.toByteArray(),
            metadataMappings = visitor.metadataMappings(),
        )
    }
}

private data class ClassExclusions(
    val keepStrings: Boolean,
    val keepMetadata: Boolean,
) {
    companion object {
        fun scan(classBytes: ByteArray): ClassExclusions {
            var keepStrings = false
            var keepMetadata = false
            ClassReader(classBytes).accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? {
                        when (descriptor) {
                            KEEP_STRING_ANNOTATION -> keepStrings = true
                            KEEP_METADATA_ANNOTATION -> keepMetadata = true
                        }
                        return null
                    }
                },
                ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
            )
            return ClassExclusions(keepStrings, keepMetadata)
        }
    }
}

private class MaxsComputingClassWriter : ClassWriter(ClassWriter.COMPUTE_MAXS)

private class StringObfuscationClassVisitor(
    private val settings: TransformSettings,
    private val processStrings: Boolean,
    private val processMetadata: Boolean,
    private val vaultBuilder: SecureVaultBuilder,
    delegate: ClassWriter,
) : ClassVisitor(Opcodes.ASM9, delegate) {
    private val staticFinalFields = mutableListOf<StaticStringField>()
    private val removedMetadata = linkedSetOf<String>()
    private var className = ""
    private var hasClassInitializer = false

    override fun visit(
        version: Int,
        access: Int,
        name: String?,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?,
    ) {
        className = name ?: error("Class name is required")
        super.visit(version, access, name, signature, superName, interfaces)
    }

    override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? {
        if (processMetadata && descriptor in KOTLIN_METADATA_ANNOTATIONS) {
            removedMetadata += "$className $descriptor"
            return null
        }
        return super.visitAnnotation(descriptor, visible)
    }

    override fun visitField(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        value: Any?,
    ): org.objectweb.asm.FieldVisitor? {
        var outputValue = value
        if (
            processStrings &&
            descriptor == STRING_DESCRIPTOR &&
            name != null &&
            value is String &&
            access and Opcodes.ACC_STATIC != 0 &&
            access and Opcodes.ACC_FINAL != 0
        ) {
            val reference = vaultBuilder.protect(value, "$className#field:$name")
            if (reference != null) {
                staticFinalFields += StaticStringField(name, reference)
                outputValue = null
            }
        }
        return super.visitField(access, name, descriptor, signature, outputValue)
    }

    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor? {
        val delegate = super.visitMethod(access, name, descriptor, signature, exceptions) ?: return null
        if (!processStrings || name == null || descriptor == null) {
            return delegate
        }
        val injectStaticFieldInitializers = name == "<clinit>"
        if (injectStaticFieldInitializers) {
            hasClassInitializer = true
        }
        return TransformingMethodVisitor(
            access = access,
            methodName = name,
            descriptor = descriptor,
            delegate = delegate,
            injectStaticFieldInitializers = injectStaticFieldInitializers,
        )
    }

    override fun visitEnd() {
        if (processStrings && !hasClassInitializer && staticFinalFields.isNotEmpty()) {
            val initializer = super.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null)
            initializer.visitCode()
            staticFinalFields.forEach { field ->
                writeVaultReference(initializer, field.reference)
                initializer.visitFieldInsn(Opcodes.PUTSTATIC, className, field.name, STRING_DESCRIPTOR)
            }
            initializer.visitInsn(Opcodes.RETURN)
            initializer.visitMaxs(0, 0)
            initializer.visitEnd()
        }
        super.visitEnd()
    }

    fun toByteArray(): ByteArray = (cv as ClassWriter).toByteArray()

    fun metadataMappings(): Set<String> = removedMetadata

    private fun writeVaultReference(methodVisitor: MethodVisitor, reference: VaultReference) {
        methodVisitor.visitLdcInsn(reference.capabilityHigh)
        methodVisitor.visitLdcInsn(reference.capabilityLow)
        methodVisitor.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            vaultBuilder.bridge.internalClassName,
            vaultBuilder.bridge.methodNames[reference.gatewayIndex],
            GATEWAY_DESCRIPTOR,
            false,
        )
    }

    private inner class TransformingMethodVisitor(
        access: Int,
        private val methodName: String,
        private val descriptor: String,
        delegate: MethodVisitor,
        private val injectStaticFieldInitializers: Boolean,
    ) : LocalVariablesSorter(Opcodes.ASM9, access, descriptor, delegate) {
        private var callSiteOrdinal = 0

        override fun visitCode() {
            super.visitCode()
            if (injectStaticFieldInitializers) {
                staticFinalFields.forEach { field ->
                    writeVaultReference(this, field.reference)
                    super.visitFieldInsn(Opcodes.PUTSTATIC, className, field.name, STRING_DESCRIPTOR)
                }
            }
        }

        override fun visitLdcInsn(value: Any?) {
            if (value is String && protectAndWrite(value, "ldc")) {
                return
            }
            super.visitLdcInsn(value)
        }

        override fun visitInvokeDynamicInsn(
            name: String?,
            descriptor: String?,
            bootstrapMethodHandle: Handle?,
            vararg bootstrapMethodArguments: Any?,
        ) {
            val recipe = stringConcatRecipe(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments)
            if (recipe == null || descriptor == null) {
                super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments)
                return
            }
            rewriteStringConcat(descriptor, recipe, bootstrapMethodArguments.drop(1))
        }

        private fun stringConcatRecipe(
            name: String?,
            descriptor: String?,
            bootstrapMethodHandle: Handle?,
            bootstrapMethodArguments: Array<out Any?>,
        ): String? {
            if (
                !settings.java9StringConcatEnabled ||
                name == null ||
                descriptor == null ||
                bootstrapMethodHandle == null ||
                bootstrapMethodHandle.tag != Opcodes.H_INVOKESTATIC ||
                bootstrapMethodHandle.owner != STRING_CONCAT_FACTORY ||
                bootstrapMethodHandle.name != "makeConcatWithConstants"
            ) {
                return null
            }

            val recipe = bootstrapMethodArguments.firstOrNull() as? String ?: return null
            val staticArguments = bootstrapMethodArguments.drop(1)
            if (staticArguments.any { it !is String && it !is Int && it !is Long && it !is Float && it !is Double }) {
                return null
            }

            val dynamicArgumentCount = recipe.count { it == DYNAMIC_ARGUMENT_MARKER }
            val staticArgumentCount = recipe.count { it == STATIC_ARGUMENT_MARKER }
            return if (
                dynamicArgumentCount == Type.getArgumentTypes(descriptor).size &&
                staticArgumentCount == staticArguments.size
            ) {
                recipe
            } else {
                null
            }
        }

        private fun rewriteStringConcat(
            descriptor: String,
            recipe: String,
            staticArguments: List<Any?>,
        ) {
            val dynamicTypes = Type.getArgumentTypes(descriptor)
            val dynamicLocals = dynamicTypes.map(::newLocal)
            dynamicTypes.indices.reversed().forEach { index ->
                val type = dynamicTypes[index]
                super.visitVarInsn(type.getOpcode(Opcodes.ISTORE), dynamicLocals[index])
            }

            val stringBuilderType = Type.getObjectType(STRING_BUILDER)
            val stringBuilderLocal = newLocal(stringBuilderType)
            super.visitTypeInsn(Opcodes.NEW, STRING_BUILDER)
            super.visitInsn(Opcodes.DUP)
            super.visitMethodInsn(Opcodes.INVOKESPECIAL, STRING_BUILDER, "<init>", "()V", false)
            super.visitVarInsn(Opcodes.ASTORE, stringBuilderLocal)

            var dynamicArgumentIndex = 0
            var staticArgumentIndex = 0
            val literal = StringBuilder()
            recipe.forEach { character ->
                when (character) {
                    DYNAMIC_ARGUMENT_MARKER -> {
                        appendLiteral(stringBuilderLocal, literal.toString())
                        literal.setLength(0)
                        appendDynamicArgument(
                            stringBuilderLocal,
                            dynamicTypes[dynamicArgumentIndex],
                            dynamicLocals[dynamicArgumentIndex],
                        )
                        dynamicArgumentIndex++
                    }

                    STATIC_ARGUMENT_MARKER -> {
                        appendLiteral(stringBuilderLocal, literal.toString())
                        literal.setLength(0)
                        appendStaticArgument(stringBuilderLocal, staticArguments[staticArgumentIndex])
                        staticArgumentIndex++
                    }

                    else -> literal.append(character)
                }
            }
            appendLiteral(stringBuilderLocal, literal.toString())
            super.visitVarInsn(Opcodes.ALOAD, stringBuilderLocal)
            super.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                STRING_BUILDER,
                "toString",
                "()Ljava/lang/String;",
                false,
            )
        }

        private fun appendLiteral(stringBuilderLocal: Int, literal: String) {
            if (literal.isEmpty()) {
                return
            }
            super.visitVarInsn(Opcodes.ALOAD, stringBuilderLocal)
            if (!protectAndWrite(literal, "concat-literal")) {
                super.visitLdcInsn(literal)
            }
            super.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                STRING_BUILDER,
                "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                false,
            )
            super.visitInsn(Opcodes.POP)
        }

        private fun appendDynamicArgument(stringBuilderLocal: Int, type: Type, local: Int) {
            super.visitVarInsn(Opcodes.ALOAD, stringBuilderLocal)
            super.visitVarInsn(type.getOpcode(Opcodes.ILOAD), local)
            super.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                STRING_BUILDER,
                "append",
                appendDescriptor(type),
                false,
            )
            super.visitInsn(Opcodes.POP)
        }

        private fun appendStaticArgument(stringBuilderLocal: Int, argument: Any?) {
            super.visitVarInsn(Opcodes.ALOAD, stringBuilderLocal)
            val appendDescriptor =
                when (argument) {
                    is String -> {
                        if (!protectAndWrite(argument, "concat-static")) {
                            super.visitLdcInsn(argument)
                        }
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;"
                    }

                    is Int -> {
                        super.visitLdcInsn(argument)
                        "(I)Ljava/lang/StringBuilder;"
                    }

                    is Long -> {
                        super.visitLdcInsn(argument)
                        "(J)Ljava/lang/StringBuilder;"
                    }

                    is Float -> {
                        super.visitLdcInsn(argument)
                        "(F)Ljava/lang/StringBuilder;"
                    }

                    is Double -> {
                        super.visitLdcInsn(argument)
                        "(D)Ljava/lang/StringBuilder;"
                    }

                    else -> error("Unsupported StringConcatFactory bootstrap argument $argument")
                }
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STRING_BUILDER, "append", appendDescriptor, false)
            super.visitInsn(Opcodes.POP)
        }

        private fun appendDescriptor(type: Type): String = when (type.sort) {
            Type.BOOLEAN -> "(Z)Ljava/lang/StringBuilder;"
            Type.CHAR -> "(C)Ljava/lang/StringBuilder;"
            Type.BYTE, Type.SHORT, Type.INT -> "(I)Ljava/lang/StringBuilder;"
            Type.LONG -> "(J)Ljava/lang/StringBuilder;"
            Type.FLOAT -> "(F)Ljava/lang/StringBuilder;"
            Type.DOUBLE -> "(D)Ljava/lang/StringBuilder;"
            Type.ARRAY, Type.OBJECT -> "(Ljava/lang/Object;)Ljava/lang/StringBuilder;"
            else -> error("Unsupported string concat argument type $type")
        }

        private fun protectAndWrite(rawValue: String, kind: String): Boolean {
            val callSiteIdentity = "$className#$methodName$descriptor:$kind:${callSiteOrdinal++}"
            val reference = vaultBuilder.protect(rawValue, callSiteIdentity) ?: return false
            writeVaultReference(this, reference)
            return true
        }
    }
}

private data class StaticStringField(val name: String, val reference: VaultReference)

private const val STRING_DESCRIPTOR = "Ljava/lang/String;"
private const val KEEP_STRING_ANNOTATION = "Lio/github/weg2022/strguard/annotation/KeepString;"
private const val KEEP_METADATA_ANNOTATION = "Lio/github/weg2022/strguard/annotation/KeepMetadata;"
private const val STRING_CONCAT_FACTORY = "java/lang/invoke/StringConcatFactory"
private const val STRING_BUILDER = "java/lang/StringBuilder"
private const val GATEWAY_DESCRIPTOR = "(JJ)Ljava/lang/String;"
private const val DYNAMIC_ARGUMENT_MARKER = '\u0001'
private const val STATIC_ARGUMENT_MARKER = '\u0002'
private val KOTLIN_METADATA_ANNOTATIONS =
    setOf(
        "Lkotlin/Metadata;",
        "Lkotlin/coroutines/jvm/internal/DebugMetadata;",
        "Lkotlin/jvm/internal/SourceDebugExtension;",
    )
