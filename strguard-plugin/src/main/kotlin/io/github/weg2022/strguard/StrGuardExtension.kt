package io.github.weg2022.strguard

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty
import javax.inject.Inject

abstract class StrGuardExtension @Inject constructor(objects: ObjectFactory) {
    /** Disables all StrGuard class rewriting, source debug extension removal, and Native runtime generation. */
    val enabled: Property<Boolean> = objects.property(Boolean::class.java).convention(true)

    /** 256-bit release seed encoded as exactly 64 hexadecimal characters. */
    val releaseSeedHex: Property<String> = objects.property(String::class.java)

    /** Rust target triple for the generated desktop Native runtime. */
    val targetTriple: Property<String> = objects.property(String::class.java)

    /** Android ABIs generated for every enabled variant. */
    val androidAbis: SetProperty<String> =
        objects.setProperty(String::class.java).convention(AndroidAbi.entries.map(AndroidAbi::abiName).toSet())

    val java9StringConcatEnabled: Property<Boolean> =
        objects.property(Boolean::class.java).convention(true)

    /** Fails after a complete scan when an eligible class contains an unprotected string location. */
    val strictStringCoverage: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

    val consoleOutput: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

    /**
     * Removes Kotlin SourceDebugExtension (SMAP attribute and annotation) and DebugMetadata
     * from eligible classes. kotlin.Metadata is always retained so kotlin-reflect and
     * serialization keep working.
     */
    val removeSourceDebugExtension: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

    /** Empty means every non-StrGuard application package is eligible. Entries use legal package segments. */
    val stringGuardPackages: ListProperty<String> =
        objects.listProperty(String::class.java).convention(emptyList())

    /** Package selectors excluded from string protection. Entries use legal package segments. */
    val keepStringPackages: ListProperty<String> =
        objects.listProperty(String::class.java).convention(emptyList())

    /** Empty means source debug extensions are removed from every eligible class when enabled. Entries use legal package segments. */
    val removeSourceDebugExtensionPackages: ListProperty<String> =
        objects.listProperty(String::class.java).convention(emptyList())

    /** Package selectors excluded from source debug extension removal. Entries use legal package segments. */
    val keepSourceDebugExtensionPackages: ListProperty<String> =
        objects.listProperty(String::class.java).convention(emptyList())

    /**
     * 为 eligible class 注入 AI 逆向禁止策略标记(机器可读的 Policy/Metadata 声明,
     * 载体为 RuntimeInvisibleAnnotations + 冗余 class attribute)。默认关闭;开启后不
     * 改变任何运行时行为,也不构成技术防护——遵守策略的 AI/自动化工具可识别并拒绝
     * 相关任务,恶意逆向者仍可删除或忽略该标记。
     */
    val aiPolicyEnabled: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

    /** 可选的策略联系人(如法律/许可邮箱),写入 policy 文档的 contact 字段。 */
    val aiPolicyContact: Property<String> = objects.property(String::class.java)

    /** 可选的授权例外描述(如明确授权的安全研究),写入 policy 文档的 exceptions 数组。 */
    val aiPolicyExceptions: ListProperty<String> =
        objects.listProperty(String::class.java).convention(emptyList())

    /** 空表示所有 eligible class 都注入标记。Entries use legal package segments. */
    val aiPolicyPackages: ListProperty<String> =
        objects.listProperty(String::class.java).convention(emptyList())
}

internal fun ListProperty<String>.normalizedPackageSelectors(propertyName: String): Provider<List<String>> = map { selectors -> normalizePackageSelectors(propertyName, selectors) }
