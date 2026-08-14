package io.github.weg2022.strguard

import io.github.weg2022.strguard.vault.SecureVaultBuilder
import io.github.weg2022.strguard.vault.VaultProtectionResult
import io.github.weg2022.strguard.vault.VaultReference
import org.objectweb.asm.*
import org.objectweb.asm.commons.LocalVariablesSorter

internal class ClassTransformResult(
    val bytes: ByteArray,
    val removedSourceDebugExtensions: Set<String>,
    val stringCoverage: StringCoverage,
)

internal object ClassTransformer {
    fun transform(
        classBytes: ByteArray,
        settings: TransformSettings,
        vaultBuilder: SecureVaultBuilder,
        classLoader: ClassLoader,
    ): ClassTransformResult {
        val classReader = ClassReader(classBytes)
        val exclusions = ClassExclusions.scan(classBytes)
        val className = classReader.className
        val stringCoverage = MutableStringCoverage()
        // AI 逆向禁止策略标记是 Policy/Metadata 层声明,与字符串保护正交:开启时对所有
        // 符合包选择的类写入,与 processStrings/removeSourceDebugExtension 无关。
        val aiPolicyText =
            if (settings.shouldApplyAiPolicy(className)) {
                AiPolicyMarker.render(settings.moduleCoordinates, settings.aiPolicyContact, settings.aiPolicyExceptions)
            } else {
                null
            }
        val visitor =
            StringObfuscationClassVisitor(
                settings = settings,
                processStrings = settings.shouldTransformStrings(className) && !exclusions.keepStrings,
                removeSourceDebugExtension = settings.shouldRemoveSourceDebugExtension(className) && !exclusions.keepSourceDebugExtension,
                aiPolicyText = aiPolicyText,
                vaultBuilder = vaultBuilder,
                stringCoverage = stringCoverage,
                delegate = FramesComputingClassWriter(classLoader),
            )
        classReader.accept(visitor, ClassReader.EXPAND_FRAMES)
        return ClassTransformResult(
            bytes = visitor.toByteArray(),
            removedSourceDebugExtensions = visitor.removedSourceDebugExtensions(),
            stringCoverage = stringCoverage.snapshot(),
        )
    }
}

private data class ClassExclusions(
    val keepStrings: Boolean,
    val keepSourceDebugExtension: Boolean,
) {
    companion object {
        fun scan(classBytes: ByteArray): ClassExclusions {
            var keepStrings = false
            var keepSourceDebugExtension = false
            ClassReader(classBytes).accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? {
                        when (descriptor) {
                            KEEP_STRING_ANNOTATION -> keepStrings = true
                            KEEP_SOURCE_DEBUG_EXTENSION_ANNOTATION -> keepSourceDebugExtension = true
                        }
                        return null
                    }
                },
                ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
            )
            return ClassExclusions(keepStrings, keepSourceDebugExtension)
        }
    }
}

/**
 * 转换会改写指令流（concat 重写引入新局部变量、LDC 替换为 gateway 调用），
 * 而 LocalVariablesSorter 修改后的帧在新布局下可能非法（分支目标帧声明了
 * 其他路径未初始化的变量类型导致 VerifyError），因此必须重算栈映射帧。
 *
 * COMPUTE_FRAMES 在合并帧时需要解析两个类型的公共父类：ASM 默认实现用
 * ClassWriter 自己的类加载器（Gradle daemon）加载类型，项目自定义类
 * （如应用源码中的 BatchTask）会 ClassNotFoundException。这里改为从
 * 编译输出目录构建的 URLClassLoader 加载（见 TransformClassesTask），
 * 加载失败的类型退化为 java/lang/Object —— 栈帧类型变宽仍能通过
 * VerifyError 校验，仅在极端情况下丢失类型精度，不会产生非法字节码。
 */
private class FramesComputingClassWriter(
    private val classesClassLoader: ClassLoader,
) : ClassWriter(COMPUTE_FRAMES) {
    override fun getCommonSuperClass(type1: String, type2: String): String {
        val first = loadClass(type1) ?: return "java/lang/Object"
        val second = loadClass(type2) ?: return "java/lang/Object"
        if (first.isAssignableFrom(second)) return type1
        if (second.isAssignableFrom(first)) return type2
        if (first.isInterface || second.isInterface) return "java/lang/Object"
        var common = first
        while (!common.isAssignableFrom(second)) {
            common = common.superclass ?: return "java/lang/Object"
        }
        return common.name.replace('.', '/')
    }

    private fun loadClass(type: String): Class<*>? = try {
        Class.forName(type.replace('/', '.'), false, classesClassLoader)
    } catch (failure: Throwable) {
        // 捕获全部 Throwable 而非仅 ClassNotFoundException:依赖 jar 不完整时
        // Class.forName 会抛 NoClassDefFoundError/LinkageError(Error 不是
        // Exception),若不放宽捕获会直接穿透导致任务失败。加载失败退化为
        // java/lang/Object:栈帧类型变宽合法,仅丢失精度。
        null
    }
}

private class StringObfuscationClassVisitor(
    private val settings: TransformSettings,
    private val processStrings: Boolean,
    private val removeSourceDebugExtension: Boolean,
    private val aiPolicyText: String?,
    private val vaultBuilder: SecureVaultBuilder,
    private val stringCoverage: MutableStringCoverage,
    delegate: ClassWriter,
) : ClassVisitor(Opcodes.ASM9, delegate) {
    private val staticFinalFields = mutableListOf<StaticStringField>()
    private val removedSourceDebugExtensions = linkedSetOf<String>()
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
        if (descriptor in KOTLIN_COMPILER_ANNOTATIONS) {
            // kotlin.Metadata 永远保留：kotlin-reflect/序列化等运行时依赖其类型信息。
            // 编译器注解整体不透传 trackedAnnotation，其字符串值(如 d1/d2)绝不当作
            // 应用字符串计数，否则 strictStringCoverage 会在 Kotlin 项目上误报。
            if (descriptor in REMOVABLE_DEBUG_ANNOTATIONS && removeSourceDebugExtension) {
                removedSourceDebugExtensions += "$className $descriptor"
                return null
            }
            return super.visitAnnotation(descriptor, visible)
        }
        return trackedAnnotation(super.visitAnnotation(descriptor, visible))
    }

    /**
     * Kotlin 编译器在 class file 中写入 SourceDebugExtension 属性(SMAP 源码映射),
     * 经 visitSource 的 debug 参数传递。该信息仅服务调试器,移除它不破坏运行时,
     * 还能隐藏源码行号映射;SourceFile(source) 保留。
     */
    override fun visitSource(source: String?, debug: String?) {
        if (removeSourceDebugExtension && debug != null) {
            removedSourceDebugExtensions += "$className SourceDebugExtension"
        }
        super.visitSource(source, if (removeSourceDebugExtension) null else debug)
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
        } else if (processStrings && descriptor == STRING_DESCRIPTOR && name != null && value is ConstantDynamic) {
            // 与 LDC 中的 condy 保持一致：String 描述符的 condy 字段值不转换但如实计数
            recordConstantDynamic(value)
        }
        val delegate = super.visitField(access, name, descriptor, signature, outputValue)
        // 字段级策略注解:唯一携带 policy 文本的级别;注入必须直调 delegate
        // (FieldVisitor.visitAnnotation 先于字段其它属性)。
        aiPolicyText?.let { policyText ->
            writePolicyAnnotation(delegate::visitAnnotation, AiPolicyMarker.FIELD_ANNOTATION_DESCRIPTOR, policyText)
        }
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
        // 方法级策略注解为无值标记:先于方法体其它内容注入(ClassWriter 要求注解先于 visitCode)。
        aiPolicyText?.let {
            delegate.visitAnnotation(AiPolicyMarker.METHOD_ANNOTATION_DESCRIPTOR, false)?.visitEnd()
        }
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
            rawDelegate = delegate,
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
        aiPolicyText?.let { policyText ->
            // 双写:类级注解为无值标记(RuntimeInvisibleAnnotations 主载体),StrGuard-AiPolicy
            // 自定义属性携带完整 policy 文本(冗余载体)。必须直调 super.visitAnnotation 绕过
            // 本类 visitAnnotation 覆写(trackedAnnotation),否则 policy 文本会被当作应用字符串
            // 计数,导致 strictStringCoverage 误报;ClassWriter 协议要求 annotation/attribute
            // 先于 visitEnd 发射。
            super.visitAnnotation(AiPolicyMarker.ANNOTATION_DESCRIPTOR, false)?.visitEnd()
            super.visitAttribute(AiPolicyAttribute(policyText.toByteArray(Charsets.UTF_8)))
        }
        super.visitEnd()
    }

    fun toByteArray(): ByteArray = (cv as ClassWriter).toByteArray()

    fun removedSourceDebugExtensions(): Set<String> = removedSourceDebugExtensions

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

    /** 在字段注解目标上注入策略注解(RuntimeInvisible,单 value 元素携带 policy 文本)。 */
    private fun writePolicyAnnotation(
        inject: (String, Boolean) -> AnnotationVisitor?,
        descriptor: String,
        policyText: String,
    ) {
        val annotation = inject(descriptor, false) ?: return
        annotation.visit(AiPolicyMarker.ELEMENT_NAME, policyText)
        annotation.visitEnd()
    }

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
        private val rawDelegate: MethodVisitor,
        private val injectStaticFieldInitializers: Boolean,
    ) : LocalVariablesSorter(Opcodes.ASM9, access, descriptor, rawDelegate) {
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

        /**
         * 所有 newLocal 分配的临时槽位都必须经 [rawDelegate] 直接发射,
         * 绝不能走 super.visitVarInsn(即 LocalVariablesSorter.remap):
         * ASM 9.7+ 重写后的 LVS,newLocalMapping 不再把新槽位注册进
         * remappedVariableIndices,super.visitVarInsn 会按"原始局部变量
         * 索引"解释这些槽位 —— 当方法里恰有同编号的原始局部变量且其
         * 重映射已建立时,临时槽的 store/load 会被重定向到该原始变量的
         * 槽上,覆盖仍然存活的变量(例如集合引用被重写的 int 参数覆盖),
         * 产生数据流非法字节码,ProGuard 优化阶段报
         * VariableEmptySlotException / VariableTypeException。
         * newLocal 与 remap 共享同一个单调递增的 nextLocal 计数器,
         * 因此新槽位永远不会与任何原始局部变量的重映射目标冲突,
         * 直发底层 visitor 是安全的(与 GeneratorAdapter 同款做法)。
         */
        private fun rewriteStringConcat(
            descriptor: String,
            recipe: String,
            staticArguments: List<Any?>,
        ) {
            val dynamicTypes = Type.getArgumentTypes(descriptor)
            val dynamicLocals = dynamicTypes.map(::newLocal)
            dynamicTypes.indices.reversed().forEach { index ->
                val type = dynamicTypes[index]
                rawDelegate.visitVarInsn(type.getOpcode(Opcodes.ISTORE), dynamicLocals[index])
            }

            val stringBuilderType = Type.getObjectType(STRING_BUILDER)
            val stringBuilderLocal = newLocal(stringBuilderType)
            super.visitTypeInsn(Opcodes.NEW, STRING_BUILDER)
            super.visitInsn(Opcodes.DUP)
            super.visitMethodInsn(Opcodes.INVOKESPECIAL, STRING_BUILDER, "<init>", "()V", false)
            rawDelegate.visitVarInsn(Opcodes.ASTORE, stringBuilderLocal)

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
            rawDelegate.visitVarInsn(Opcodes.ALOAD, stringBuilderLocal)
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
            rawDelegate.visitVarInsn(Opcodes.ALOAD, stringBuilderLocal)
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
            rawDelegate.visitVarInsn(Opcodes.ALOAD, stringBuilderLocal)
            if (type.descriptor == CHAR_ARRAY_DESCRIPTOR) {
                // StringConcatFactory 对 char[] 是复制语义（new String(char[]) 复制数组）；
                // 直接 StringBuilder.append(char[]) 只持有引用，数组后续被修改会改变结果。
                super.visitTypeInsn(Opcodes.NEW, "java/lang/String")
                super.visitInsn(Opcodes.DUP)
                rawDelegate.visitVarInsn(Opcodes.ALOAD, local)
                super.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/String", "<init>", "([C)V", false)
                super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    STRING_BUILDER,
                    "append",
                    "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                    false,
                )
                super.visitInsn(Opcodes.POP)
                return
            }
            rawDelegate.visitVarInsn(type.getOpcode(Opcodes.ILOAD), local)
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
            rawDelegate.visitVarInsn(Opcodes.ALOAD, stringBuilderLocal)
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
                // makeConcat 变体没有 recipe 与静态参数：记录一次"跳过了整个拼接"
                stringCoverage.recordSkipped(reason)
                return
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
private const val CHAR_ARRAY_DESCRIPTOR = "[C"
private const val KEEP_STRING_ANNOTATION = "Lio/github/weg2022/strguard/annotation/KeepString;"
private const val KEEP_SOURCE_DEBUG_EXTENSION_ANNOTATION = "Lio/github/weg2022/strguard/annotation/KeepSourceDebugExtension;"
private const val STRING_CONCAT_FACTORY = "java/lang/invoke/StringConcatFactory"
private const val STRING_BUILDER = "java/lang/StringBuilder"
private const val GATEWAY_DESCRIPTOR = "(JJ)Ljava/lang/String;"
private const val DYNAMIC_ARGUMENT_MARKER = '\u0001'
private const val STATIC_ARGUMENT_MARKER = '\u0002'

// 编译器注解全集：无论移除开关如何，都透传且不跟踪其字符串值(见 visitAnnotation)
private val KOTLIN_COMPILER_ANNOTATIONS =
    setOf(
        "Lkotlin/Metadata;",
        "Lkotlin/coroutines/jvm/internal/DebugMetadata;",
        "Lkotlin/jvm/internal/SourceDebugExtension;",
    )

// 可移除的纯调试注解；kotlin.Metadata 永远不在此集合
private val REMOVABLE_DEBUG_ANNOTATIONS =
    setOf(
        "Lkotlin/coroutines/jvm/internal/DebugMetadata;",
        "Lkotlin/jvm/internal/SourceDebugExtension;",
    )

private fun isStringConcatFactory(bootstrapMethodHandle: Handle?): Boolean = bootstrapMethodHandle != null &&
    bootstrapMethodHandle.tag == Opcodes.H_INVOKESTATIC &&
    bootstrapMethodHandle.owner == STRING_CONCAT_FACTORY &&
    (bootstrapMethodHandle.name == "makeConcatWithConstants" || bootstrapMethodHandle.name == "makeConcat")
