# StrGuard 中文版

[English](README.md) | [简体中文](README.zh-CN.md)

StrGuard 2 是一个使用 ASM 改写 JVM class 文件的 Kotlin Gradle 插件。符合条件的字符串字面量会被替换成构建专属 capability 调用，并由生成的 Rust JNI 动态库从 ChaCha20-Poly1305 加密 vault 中解码。

StrGuard 用于提高静态提取成本，不是 Secret Store。能够控制或注入运行进程的代码，仍可在值成为 JVM `String` 后观察它。不要把密码、签名私钥或长期 Credential 写进应用代码。

## 支持范围

* Java、Java Library、Application 和 Kotlin/JVM 模块
* 含 Java 或 Kotlin class 的 Android Application 与 Android Library variant
* Kotlin Multiplatform JVM 与 Android target；JS、Native、Wasm保持 pass-through
* Windows x64/arm64、Linux glibc x64/arm64、macOS x64/arm64
* Android `armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64`
* ProGuard/R8 verified artifact与 Compose Desktop release ProGuard集成
* `LDC`、static final字符串、Java 9+ `StringConcatFactory`、Kotlin字符串模板
* 可选 Kotlin metadata annotation移除

支持的 plugin ID为 `java`、`java-library`、`application`、`org.jetbrains.kotlin.jvm`、`org.jetbrains.kotlin.android`、`com.android.application`、`com.android.library`、`org.jetbrains.kotlin.multiplatform`。Kotlin Android必须搭配 Android Application或 Library插件。

## 环境要求

* 使用 JDK 17 或 21 运行 Gradle；受保护输出最低为 Java 11或原始 bytecode版本
* Gradle 8.14.4
* Rust/Cargo 1.94.1，以及目标平台与 linker
* Kotlin Gradle Plugin 2.1.21
* Android Gradle Plugin 8.13.2、Android SDK 34、NDK 27.2.12479018

`STRGUARD_CARGO_EXECUTABLE` 可指定 Cargo。其同目录 `rustc` 与 linker会进入 toolchain fingerprint。

## 安装

在 `2.0.0` 发布并通过外部 Plugin Portal smoke gate前，请使用 composite build。GA在 Portal可见后，在每个拥有编译 class的 JVM或 Android模块中应用：

```kotlin
plugins {
    id("io.github.weg2022.strguard") version "2.0.0"
}
```

不要只在 aggregator root project应用插件。

## Release seed

每个启用保护的 build都需要一个 256-bit seed，编码为恰好 64 个十六进制字符：

```text
STRGUARD_RELEASE_SEED_HEX=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
```

请由 CI注入，不要打印或提交生产 seed。相同 seed、module identity、target、输入与 toolchain会生成相同输出；不同信任域应使用不同 seed。

## 配置

```kotlin
strGuard {
    enabled.set(true)
    releaseSeedHex.set(providers.environmentVariable("STRGUARD_RELEASE_SEED_HEX"))

    // 仅 Desktop JVM/KMP；默认当前 host。
    targetTriple.set("x86_64-pc-windows-msvc")

    // 仅 Android；默认全部 4 个官方 ABI。
    androidAbis.set(setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))

    stringGuardPackages.set(listOf("com.example.app"))
    keepStringPackages.set(listOf("com.example.app.generated"))

    java9StringConcatEnabled.set(true)
    consoleOutput.set(false)

    removeMetadata.set(false)
    removeMetadataPackages.set(listOf("com.example.app"))
    keepMetadataPackages.set(listOf("com.example.app.reflective"))
}
```

| 属性 | 默认值 | 作用 |
| --- | --- | --- |
| `enabled` | `true` | 关闭后不改写、不校验 seed/target/API/ABI，也不执行 Native任务。 |
| `releaseSeedHex` | `STRGUARD_RELEASE_SEED_HEX` | 启用时必需的 64 字符十六进制 seed。 |
| `targetTriple` | 环境变量或当前 host | Desktop JVM/KMP Rust target；每个 JAR一个 runtime。 |
| `androidAbis` | 4 个官方 ABI | 非空 Android ABI集合；AGP filter与最终 split必须是其子集。 |
| `java9StringConcatEnabled` | `true` | 保护受支持的 Java 9+ concat recipe常量。 |
| `consoleOutput` | `false` | 输出 schema化 summary，不输出字面量或 key material。 |
| `removeMetadata` | `false` | 移除符合条件的 Kotlin metadata annotation。 |
| `stringGuardPackages` | 空 | include前缀；空表示全部非 StrGuard应用 class。 |
| `keepStringPackages` | 空 | 字符串保护排除前缀。 |
| `removeMetadataPackages` | 空 | metadata移除 include前缀。 |
| `keepMetadataPackages` | 空 | metadata移除排除前缀。 |

package可用点分或 slash分隔并包含子 package；keep列表优先。

支持的 Desktop target：

```text
x86_64-pc-windows-msvc
aarch64-pc-windows-msvc
x86_64-unknown-linux-gnu
aarch64-unknown-linux-gnu
x86_64-apple-darwin
aarch64-apple-darwin
```

Android使用 ABI-neutral transformed classes与 vault，再构建对应 Rust target。Native runtime最低 API为 21。`minSdk < 21`，或 ABI filter/最终 split不在 `androidAbis`中时会 fail-fast。AAR marker记录精确 ABI集合与 minSdk。

## 排除 class

插件会向 Java/Kotlin编译提供 `KeepString` 与 `KeepMetadata`：

```kotlin
import io.github.weg2022.strguard.annotation.KeepString

@KeepString
class DiagnosticStrings {
    fun value() = "这个字面量保留在 class 文件中"
}
```

## 输出与 shrinker

```text
build/strguard/classes/main
build/strguard/native-input/main
build/strguard/native-resources/main
build/reports/strguard/main
```

KMP JVM输出包含 target名，Android输出使用 variant名。JVM JAR包含随机 bridge、module-unique loader、单一 target Native library、mandatory shrinker rules与 versioned marker。Android APK使用 `lib/<abi>`，AAR使用 `jni/<abi>`。

Desktop shrinker唯一支持的顺序：

```text
compile -> StrGuard protected JAR -> ProGuard/R8 -> StrGuard verifier -> publication/distribution
```

```kotlin
val artifact = strGuardArtifacts.jvm("main")
val shrink = tasks.register<proguard.gradle.ProGuardTask>("proguardMain") {
    dependsOn(artifact.protectedJar)
    injars(artifact.protectedJar.get().asFile)
    configuration(artifact.requiredShrinkerRules.get().asFile)
}
val verifiedJar = artifact.verifyShrunkJar(
    shrink.map { layout.buildDirectory.file("shrinker/raw.jar").get() },
    "proguard:7.9.1",
)
```

只允许发布或分发 verified output。应用仍需自行提供 public API、reflection、serialization、`ServiceLoader`、main class、Kotlin metadata与 framework规则。Compose Desktop release ProGuard会自动接线，但不会改变用户 shrinker设置。

## 安全与兼容

* 防护目标是普通 constant-pool scan、`strings` 与通用静态提取，不防止运行时受控代码读取。
* transformed classes、vault与 Native library属于同一构建单元，必须一起分发。
* 敏感 task无法被 consumer重新启用 Gradle Build Cache；普通 up-to-date检查保留。
* ChaCha20-Poly1305 authentication failure会失败退出。JNI返回 interned string以保持 Java/Kotlin literal identity。
* Cargo仅接收 allowlist环境，不接收 raw seed；输出有界，timeout/interruption会清理 descendant。
* derived byte array会清零；JVM `String`无法保证从 managed memory擦除。
* 输出先 staging，再作为一个可 rollback的 output set提交。
* Desktop只处理 `java.io.tmpdir/strguard/sg2-*` 下的生成库名，不递归删除未知文件。
* Java static final字符串与 Kotlin `const val`会失去 `ConstantValue`并在 `<clinit>`初始化，不要作为跨模块编译期常量或 annotation参数。
* annotation字符串、任意 `ConstantDynamic`、不支持的 `invokedynamic`、空字符串和极大字符串保持不变。
* metadata移除可能破坏 reflection、serialization和 compiler tooling。

安全漏洞请使用 GitHub private vulnerability reporting。不要在公开 Issue中提交 seed、key share、vault、Credential、exploit细节或私有应用代码。

## Report与迁移

每次 transform都会写入 `build/reports/strguard/<target-or-variant>/summary.txt`，格式为 Java properties，`schemaVersion=1`。稳定字段包括 `enabled`、`runtimeTarget`、`inputClasses`、`eligibleClasses`、`matchedClasses`、`skippedClasses`、`protectedStrings`、`removedMetadata`、`unmatchedKeepStringPackages`、`unmatchedKeepMetadataPackages`。报告不包含字面量、seed、share或 decoded value；consumer必须忽略未知的 schema 1可选字段。

StrGuard 2修改了 vault、Native runtime、task graph与 shrinker契约。所有受保护模块都必须 clean。v3 runtime拒绝 vault v2。更改 seed、target、plugin或 Native toolchain时应删除模块 `build/`。不要混用 1.x或 pre-GA 2.0 class与另一份 Native library。

## 开发

[`samples`](samples/README.md) 覆盖所有支持的项目类型。

```powershell
.\gradlew.bat :strguard-plugin:check :strguard-plugin:validatePlugins
cargo fmt --manifest-path native/strguard-runtime/Cargo.toml -- --check
cargo clippy --manifest-path native/strguard-runtime/Cargo.toml --all-targets --locked -- -D warnings
cargo test --manifest-path native/strguard-runtime/Cargo.toml --locked
```

设置 `STRGUARD_ANDROID_NATIVE_TEST=true` 和 `ANDROID_NDK_VERSION=27.2.12479018` 后，functional test会编译全部 4 个 Android runtime。本地测试不启动 emulator；ART证据由 Firebase Test Lab提供。

## CI与发布

`ci.yml` 覆盖 JDK 17/21、Java 11、6 个 Desktop runner、Android 4 ABI/R8、Rust audit/deny/coverage/performance/RSS、可复现性、dependency verification、distribution与 samples。`android-device-farm.yml` 验证 4 个实际 ART进程 ABI，并运行正常与 tampered-vault APK。

`release.yml` 是受保护的 RC/GA workflow。GA要求同一 RC commit已观察 7 天且没有 open P0/P1 Issue，然后重跑 assurance gate、验证外部 Portal JVM/Android consumer、导出 SPDX SBOM、生成 checksum，最后创建 immutable tag与 GitHub Release。

仓库必须配置受保护的 `release` environment、Portal Credential、Google Workload Identity、Test Lab project，以及覆盖 API 21与 API 34+的 4-entry `FIREBASE_DEVICE_MATRIX_JSON`。

## License

Apache License 2.0，见 [LICENSE](LICENSE)。
