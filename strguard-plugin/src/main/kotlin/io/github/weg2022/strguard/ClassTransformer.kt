package io.github.weg2022.strguard

import io.github.weg2022.strguard.vault.SecureVaultBuilder
import io.github.weg2022.strguard.vault.VaultProtectionResult
import io.github.weg2022.strguard.vault.VaultReference
import org.objectweb.asm.*
import org.objectweb.asm.commons.LocalVariablesSorter

internal class ClassTransformResult(
    val bytes: ByteArray,
    val metadataMappings: Set<String>,
    val stringCoverage: StringCoverage,
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
        val stringCoverage = MutableStringCoverage()
        val visitor =
            StringObfuscationClassVisitor(
                settings = settings,
                processStrings = settings.shouldTransformStrings(className) && !exclusions.keepStrings,
                processMetadata = settings.shouldRemoveMetadata(className) && !exclusions.keepMetadata,
                vaultBuilder = vaultBuilder,
                stringCoverage = stringCoverage,
                delegate = MaxsComputingClassWriter(),
            )
        classReader.accept(visitor, ClassReader.EXPAND_FRAMES)
        return ClassTransformResult(
            bytes = visitor.toByteArray(),
            metadataMappings = visitor.metadataMappings(),
            stringCoverage = stringCoverage.snapshot(),
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

private class MaxsComputingClassWriter : ClassWriter(COMPUTE_MAXS)

private class StringObfuscationClassVisitor(
    private val settings: TransformSettings,
    private val processStrings: Boolean,
    private val processMetadata: Boolean,
    private val vaultBuilder: SecureVaultBuilder,
    private val stringCoverage: MutableStringCoverage,
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
        if (descriptor in KOTLIN_METADATA_ANNOTATIONS) {
            if (processMetadata) {
                removedMetadata += "$className $descriptor"
                return null
            }
            return super.visitAnnotation(descriptor, visible)
        }
        return trackedAnnotation(super.visitAnnotation(descriptor, visible))
    }

    override fun visitTypeAnnotation(
        typeRef: Int,
        typePath: TypePath?,
        descriptor: String?,
        visible: Boolean,
    ): AnnotationVisitor? = trackedAnnotation(super.visitTypeAnnotation(typeRef, typePath, descriptor, visible))

    override fun visitRecordComponent(
        name: String?,
        descriptor: String?,
        signature: String?,
    ): RecordComponentVisitor? {
        val delegate = super.visitRecordComponent(name, descriptor, signature)
        return if (processStrings && delegate != null) TrackingRecordComponentVisitor(delegate) else delegate
    }

    override fun visitAttribute(attribute: Attribute?) {
        if (processStrings && attribute != null) stringCoverage.recordUnknownAttribute()
        super.visitAttribute(attribute)
    }

    override fun visitField(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        value: Any?,
    ): FieldVisitor? {
        var outputValue = value
        if (processStrings && descriptor == STRING_DESCRIPTOR && name != null && value is String) {
            if (access and Opcodes.ACC_STATIC != 0 && access and Opcodes.ACC_FINAL != 0) {
                val reference = protect(value, "$className#field:$name")
                if (reference != null) {
                    staticFinalFields += StaticStringField(name, reference)
                    outputValue = null
                }
            } else {
                stringCoverage.recordSkipped(value, StringSkipReason.UNSUPPORTED_FIELD_STRING)
            }
        }
        val delegate = super.visitField(access, name, descriptor, signature, outputValue)
        return if (processStrings && delegate != null) TrackingFieldVisitor(delegate) else delegate
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

    private fun protect(rawValue: String, callSiteIdentity: String): VaultReference? = when (val result = vaultBuilder.protect(rawValue, callSiteIdentity)) {
        is VaultProtectionResult.Protected -> {
            stringCoverage.recordProtected()
            result.reference
        }

        VaultProtectionResult.Empty -> {
            stringCoverage.recordSkipped(StringSkipReason.EMPTY_STRING)
            null
        }

        VaultProtectionResult.TooLarge -> {
            stringCoverage.recordSkipped(StringSkipReason.OVERSIZED_STRING)
            null
        }
    }

    private fun trackedAnnotation(delegate: AnnotationVisitor?): AnnotationVisitor? = if (processStrings && delegate != null) TrackingAnnotationVisitor(delegate) else delegate

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
            if (value is ConstantDynamic) {
                recordConstantDynamic(value)
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
                recordUnsupportedInvokeDynamic(bootstrapMethodHandle, bootstrapMethodArguments)
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
            val reference = protect(rawValue, callSiteIdentity) ?: return false
            writeVaultReference(this, reference)
            return true
        }

        override fun visitAnnotationDefault(): AnnotationVisitor? = trackedAnnotation(super.visitAnnotationDefault())

        override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? = trackedAnnotation(super.visitAnnotation(descriptor, visible))

        override fun visitTypeAnnotation(
            typeRef: Int,
            typePath: TypePath?,
            descriptor: String?,
            visible: Boolean,
        ): AnnotationVisitor? = trackedAnnotation(super.visitTypeAnnotation(typeRef, typePath, descriptor, visible))

        override fun visitParameterAnnotation(
            parameter: Int,
            descriptor: String?,
            visible: Boolean,
        ): AnnotationVisitor? = trackedAnnotation(super.visitParameterAnnotation(parameter, descriptor, visible))

        override fun visitInsnAnnotation(
            typeRef: Int,
            typePath: TypePath?,
            descriptor: String?,
            visible: Boolean,
        ): AnnotationVisitor? = trackedAnnotation(super.visitInsnAnnotation(typeRef, typePath, descriptor, visible))

        override fun visitTryCatchAnnotation(
            typeRef: Int,
            typePath: TypePath?,
            descriptor: String?,
            visible: Boolean,
        ): AnnotationVisitor? = trackedAnnotation(super.visitTryCatchAnnotation(typeRef, typePath, descriptor, visible))

        override fun visitLocalVariableAnnotation(
            typeRef: Int,
            typePath: TypePath?,
            start: Array<out Label>?,
            end: Array<out Label>?,
            index: IntArray?,
            descriptor: String?,
            visible: Boolean,
        ): AnnotationVisitor? = trackedAnnotation(
            super.visitLocalVariableAnnotation(
                typeRef,
                typePath,
                start,
                end,
                index,
                descriptor,
                visible,
            ),
        )

        override fun visitAttribute(attribute: Attribute?) {
            if (attribute != null) stringCoverage.recordUnknownAttribute()
            super.visitAttribute(attribute)
        }

        private fun recordUnsupportedInvokeDynamic(
            bootstrapMethodHandle: Handle?,
            bootstrapMethodArguments: Array<out Any?>,
        ) {
            val isStringConcat = isStringConcatFactory(bootstrapMethodHandle)
            val reason =
                when {
                    isStringConcat && !settings.java9StringConcatEnabled -> StringSkipReason.DISABLED_STRING_CONCAT
                    isStringConcat -> StringSkipReason.UNSUPPORTED_STRING_CONCAT
                    else -> StringSkipReason.UNSUPPORTED_INVOKEDYNAMIC
                }
            if (isStringConcat) {
                val recipe = bootstrapMethodArguments.firstOrNull() as? String
                if (recipe != null) {
                    recordRecipeLiterals(recipe, reason)
                    bootstrapMethodArguments.drop(1).forEach { argument ->
                        recordBootstrapString(argument, reason)
                    }
                    return
                }
            }
            bootstrapMethodArguments.forEach { argument -> recordBootstrapString(argument, reason) }
        }

        private fun recordRecipeLiterals(recipe: String, reason: StringSkipReason) {
            val literal = StringBuilder()
            recipe.forEach { character ->
                if (character == DYNAMIC_ARGUMENT_MARKER || character == STATIC_ARGUMENT_MARKER) {
                    if (literal.isNotEmpty()) {
                        stringCoverage.recordSkipped(literal.toString(), reason)
                        literal.setLength(0)
                    }
                } else {
                    literal.append(character)
                }
            }
            if (literal.isNotEmpty()) stringCoverage.recordSkipped(literal.toString(), reason)
        }

        private fun recordBootstrapString(value: Any?, reason: StringSkipReason) {
            when (value) {
                is String -> stringCoverage.recordSkipped(value, reason)
                is ConstantDynamic -> recordConstantDynamic(value)
            }
        }

        private fun recordConstantDynamic(value: ConstantDynamic) {
            if (value.descriptor == STRING_DESCRIPTOR) {
                stringCoverage.recordSkipped(StringSkipReason.CONSTANT_DYNAMIC)
            }
            for (index in 0 until value.bootstrapMethodArgumentCount) {
                recordBootstrapString(
                    value.getBootstrapMethodArgument(index),
                    StringSkipReason.CONSTANT_DYNAMIC,
                )
            }
        }
    }

    private inner class TrackingAnnotationVisitor(delegate: AnnotationVisitor) : AnnotationVisitor(Opcodes.ASM9, delegate) {
        override fun visit(name: String?, value: Any?) {
            if (value is String) {
                stringCoverage.recordSkipped(value, StringSkipReason.ANNOTATION_STRING)
            }
            super.visit(name, value)
        }

        override fun visitAnnotation(name: String?, descriptor: String?): AnnotationVisitor? = trackedAnnotation(super.visitAnnotation(name, descriptor))

        override fun visitArray(name: String?): AnnotationVisitor? = trackedAnnotation(super.visitArray(name))
    }

    private inner class TrackingFieldVisitor(delegate: FieldVisitor) : FieldVisitor(Opcodes.ASM9, delegate) {
        override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? = trackedAnnotation(super.visitAnnotation(descriptor, visible))

        override fun visitTypeAnnotation(
            typeRef: Int,
            typePath: TypePath?,
            descriptor: String?,
            visible: Boolean,
        ): AnnotationVisitor? = trackedAnnotation(super.visitTypeAnnotation(typeRef, typePath, descriptor, visible))

        override fun visitAttribute(attribute: Attribute?) {
            if (attribute != null) stringCoverage.recordUnknownAttribute()
            super.visitAttribute(attribute)
        }
    }

    private inner class TrackingRecordComponentVisitor(delegate: RecordComponentVisitor) : RecordComponentVisitor(Opcodes.ASM9, delegate) {
        override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? = trackedAnnotation(super.visitAnnotation(descriptor, visible))

        override fun visitTypeAnnotation(
            typeRef: Int,
            typePath: TypePath?,
            descriptor: String?,
            visible: Boolean,
        ): AnnotationVisitor? = trackedAnnotation(super.visitTypeAnnotation(typeRef, typePath, descriptor, visible))

        override fun visitAttribute(attribute: Attribute?) {
            if (attribute != null) stringCoverage.recordUnknownAttribute()
            super.visitAttribute(attribute)
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

private fun isStringConcatFactory(bootstrapMethodHandle: Handle?): Boolean = bootstrapMethodHandle != null &&
    bootstrapMethodHandle.tag == Opcodes.H_INVOKESTATIC &&
    bootstrapMethodHandle.owner == STRING_CONCAT_FACTORY &&
    bootstrapMethodHandle.name == "makeConcatWithConstants"
