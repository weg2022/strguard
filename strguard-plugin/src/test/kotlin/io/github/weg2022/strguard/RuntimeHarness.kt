package io.github.weg2022.strguard

import io.github.weg2022.strguard.crypto.CryptoPrimitives
import io.github.weg2022.strguard.vault.BridgeModel
import io.github.weg2022.strguard.vault.SecureVaultBuilder
import io.github.weg2022.strguard.vault.VaultProtectionResult
import io.github.weg2022.strguard.vault.VaultReference
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * 单元测试层的运行时端到端验证基建：转换 fixture 字节码后，用自定义 ClassLoader 注入
 * 一个"替身 bridge"（与真实 bridge 同名同方法签名，方法体为 capability→明文 比较链），
 * 从而在无需 native 库的前提下真正加载并执行转换后的类。
 *
 * 替身 bridge 依赖 [RecordingVaultBuilder] 在 protect 阶段捕获 (capability, plaintext) 映射，
 * 因为 capability 由 HMAC/HKDF 密钥链派生，测试侧无法复算。替身 bridge 内嵌明文是测试
 * 基建的必要部分——真实产品中明文只存在于加密的 vault.bin，由 native 库还原。
 */
internal class RuntimeHarness(
    private val moduleIdentity: String = "io.github.weg2022:runtime-fixture::test",
) {
    fun transformAndLoad(
        original: ByteArray,
        settings: TransformSettings = defaultSettings(),
        extraFixtures: Map<String, ByteArray> = emptyMap(),
    ): LoadedFixture {
        val recorded = mutableListOf<ProtectedRecord>()
        val (transformedBytes, coverage, bridgeModel) =
            RecordingVaultBuilder(TEST_SEED, moduleIdentity, CryptoPrimitives.sha256(original), recorded).use { builder ->
                val result = ClassTransformer.transform(original, settings, builder, ClassTransformer::class.java.classLoader)
                Triple(result.bytes, result.stringCoverage, builder.bridge)
            }
        val bridgeBytes = stubBridgeBytes(bridgeModel, recorded)
        val loader = FixtureClassLoader(mapOf(bridgeModel.internalClassName to bridgeBytes))
        loader.defineFixture(ClassReader(original).className, transformedBytes)
        extraFixtures.forEach { (internalName, bytes) -> loader.defineFixture(internalName, bytes) }
        return LoadedFixture(transformedBytes, coverage, bridgeInternalClassName = bridgeModel.internalClassName, loader = loader)
    }

    fun defaultSettings(): TransformSettings = TransformSettings(
        enabled = true,
        java9StringConcatEnabled = true,
        strictStringCoverage = true,
        removeSourceDebugExtension = false,
        stringGuardPackages = listOf("sample"),
        keepStringPackages = emptyList(),
        removeSourceDebugExtensionPackages = emptyList(),
        keepSourceDebugExtensionPackages = emptyList(),
    )

    private fun stubBridgeBytes(bridge: BridgeModel, records: List<ProtectedRecord>): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        writer.visit(
            Opcodes.V11,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
            bridge.internalClassName,
            null,
            "java/lang/Object",
            null,
        )
        val recordsByGateway = records.groupBy { it.reference.gatewayIndex }
        bridge.methodNames.forEachIndexed { index, methodName ->
            val method = writer.visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                methodName,
                "(JJ)Ljava/lang/String;",
                null,
                null,
            )
            method.visitCode()
            recordsByGateway[index].orEmpty().forEach { record ->
                val next = Label()
                method.visitLdcInsn(record.reference.capabilityHigh)
                method.visitVarInsn(Opcodes.LLOAD, 0)
                method.visitInsn(Opcodes.LCMP)
                method.visitJumpInsn(Opcodes.IFNE, next)
                method.visitLdcInsn(record.reference.capabilityLow)
                method.visitVarInsn(Opcodes.LLOAD, 2)
                method.visitInsn(Opcodes.LCMP)
                method.visitJumpInsn(Opcodes.IFNE, next)
                method.visitLdcInsn(record.plaintext)
                method.visitInsn(Opcodes.ARETURN)
                method.visitLabel(next)
            }
            method.visitLdcInsn("unreachable:$index")
            method.visitInsn(Opcodes.ARETURN)
            method.visitMaxs(0, 0)
            method.visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }
}

internal class LoadedFixture(
    val transformedBytes: ByteArray,
    val coverage: StringCoverage,
    val bridgeInternalClassName: String,
    private val loader: ClassLoader,
) {
    /**
     * 反射调用 fixture 的 public static 方法（按方法名 + 参数个数匹配，fixture 方法名唯一）。
     * 原始类型参数经自动装箱/拆箱传递。
     */
    fun call(className: String, method: String, vararg args: Any?): Any? {
        val target = loader.loadClass(className)
        val methodInstance = target.methods.first { candidate ->
            candidate.name == method && candidate.parameterCount == args.size
        }
        return methodInstance.invoke(null, *args)
    }

    fun stringValue(className: String, method: String, vararg args: Any?): String = call(className, method, *args) as String

    /** 转换后的类字节码中不得残留任何受保护明文（替身 bridge 内嵌明文是基建必要部分，不检查）。 */
    fun assertPlaintextAbsent(vararg secrets: String) {
        val text = transformedBytes.toString(StandardCharsets.ISO_8859_1)
        secrets.forEach { secret ->
            assertFalse(text.contains(secret), "transformed bytes must not contain plaintext '$secret'")
        }
    }

    fun assertCoverage(
        protectedCount: Long,
        vararg skipped: Pair<StringSkipReason, Long>,
    ) {
        assertEquals(protectedCount, coverage.protectedStrings, "protectedStrings")
        skipped.forEach { (reason, expected) ->
            assertEquals(expected, coverage.skipped(reason), reason.reportProperty)
        }
    }
}

/**
 * 捕获每次成功保护产生的 (capability, plaintext) 映射，供替身 bridge 查表。
 * 与真实 builder 相同输入派生相同密钥，capability 完全一致。
 */
private class RecordingVaultBuilder(
    seed: String,
    moduleIdentity: String,
    inputDigest: ByteArray,
    private val recorded: MutableList<ProtectedRecord>,
) : SecureVaultBuilder(seed, moduleIdentity, inputDigest, JvmNativeTarget.WINDOWS_X64) {
    override fun protect(rawValue: String, callSiteIdentity: String): VaultProtectionResult {
        val result = super.protect(rawValue, callSiteIdentity)
        if (result is VaultProtectionResult.Protected) {
            recorded += ProtectedRecord(result.reference, rawValue)
        }
        return result
    }
}

private class ProtectedRecord(val reference: VaultReference, val plaintext: String)

/**
 * 加载 fixture 类与替身 bridge 的隔离类加载器；fixture 是纯 Java 字节码，
 * 其依赖（String/StringBuilder/数组）全部来自引导类。
 */
private class FixtureClassLoader(
    classes: Map<String, ByteArray>,
    parent: ClassLoader = ClassLoader.getPlatformClassLoader(),
) : ClassLoader(parent) {
    private val classes = classes.toMutableMap()

    fun defineFixture(className: String, bytes: ByteArray) {
        classes[className] = bytes
    }

    /**
     * 入口统一规范化为点分隔名：斜杠名会使 findLoadedClass 无法命中 JVM 字典
     * （字典键是点分隔二进制名），导致重复 defineClass 抛 LinkageError。
     */
    override fun loadClass(name: String, resolve: Boolean): Class<*> = super.loadClass(name.replace('/', '.'), resolve)

    override fun findClass(name: String): Class<*> {
        val internalName = name.replace('.', '/')
        val bytes = classes[internalName] ?: throw ClassNotFoundException(name)
        return defineClass(name, bytes, 0, bytes.size)
    }
}

internal const val TEST_SEED =
    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
