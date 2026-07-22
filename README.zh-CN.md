# StrGuard 中文版

[English](README.md) | [简体中文](README.zh-CN.md)

StrGuard 是一个使用 ASM 改写 JVM class 文件的 Kotlin Gradle 插件。符合条件的字符串字面量会被替换成构建专属 capability 调用，并由生成的 Rust JNI 动态库从 ChaCha20-Poly1305 加密 vault 中解码。

StrGuard 提供的是提高静态提取成本的 authenticated obfuscation，不保证制品分发后的密码学保密性。每份制品都必然包含 runtime 解码所需材料；能够控制或注入运行进程的代码，也能在值成为 JVM `String` 后观察它。不要把密码、签名私钥或长期 Credential 写进应用代码。

## 支持范围

* Java、Java Library、Application 和 Kotlin/JVM 模块
* 含 Java 或 Kotlin class 的 Android Application 与 Android Library variant
* Kotlin Multiplatform JVM 与 Android target；JS、Native、Wasm保持 pass-through
* Windows x64/arm64、Linux glibc x64/arm64、macOS x64/arm64
* Android `armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64`
* ProGuard/R8 verified artifact与 Compose Desktop release ProGuard集成
* `LDC`、static final 字符串、Java 9+ `StringConcatFactory`、Kotlin 字符串模板，以及数组、集合、switch/when、lambda、reflection call 等普通 bytecode 中的同类字面量
* 可选 Kotlin metadata annotation移除

支持的 plugin ID为 `java`、`java-library`、`application`、`org.jetbrains.kotlin.jvm`、`org.jetbrains.kotlin.android`、`com.android.application`、`com.android.library`、`org.jetbrains.kotlin.multiplatform`。Kotlin Android必须搭配 Android Application或 Library插件。

## 环境要求

* 使用 JDK 17 或 21 运行 Gradle；受保护输出最低为 Java 11 或原始 bytecode 版本
* ASM 9.10.1 支持并原样保留 Java 11 到 Java 27 的 class（major 55-71），包括 Java 27 preview class；范围外输入不受支持
* 发布插件会把 ASM shade 并 relocate 到私有 namespace，因此 Gradle、`buildSrc` 或其他插件预加载的旧 ASM 无法降低 class-file 支持上限
* Gradle 8.14.4
* Rust/Cargo 1.94.1，以及目标平台与 linker
* Kotlin Gradle Plugin 2.1.21
* Android Gradle Plugin 8.13.2、Android SDK 34、NDK 27.2.12479018

`STRGUARD_CARGO_EXECUTABLE` 可指定 Cargo。其同目录 `rustc` 与选定的 linker/archiver 会进入 toolchain fingerprint。

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
    strictStringCoverage.set(true)
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
| `strictStringCoverage` | `false` | 写入完整聚合 coverage；扫描结束后，如选定 class 中仍有不支持的非空字符串位置或未知 custom attribute，则构建失败。 |
| `consoleOutput` | `false` | 输出 schema化 summary，不输出字面量或 key material。 |
| `removeMetadata` | `false` | 移除符合条件的 Kotlin metadata annotation。 |
| `stringGuardPackages` | 空 | include前缀；空表示全部非 StrGuard 应用 class。 |
| `keepStringPackages` | 空 | 字符串保护排除前缀。 |
| `removeMetadataPackages` | 空 | metadata移除 include前缀。 |
| `keepMetadataPackages` | 空 | metadata移除排除前缀。 |

package可用点分或 slash分隔并包含子 package；keep列表优先。

## 字符串覆盖范围

在选定的应用 class 内，只要最终编译为普通字符串常量，StrGuard 就会处理，而不取决于源码写法。已支持 method literal、static final field、Kotlin `const val`、数组/集合元素、Java `switch` 与 Kotlin `when` 分支、lambda body、reflection argument、Java 9+ concat recipe fragment 和 Kotlin template。值会逐个保留原始 UTF-16 code unit，包括 NUL、控制字符、换行、非 BMP 字符和未配对 surrogate。单个受保护值上限为 30,000 个 UTF-16 code unit，即 60,000 UTF-16LE bytes。

StrGuard 无法透明保护与应用相关的所有字符串：

* annotation value 与 default 属于 class-file 和 framework contract；
* XML、JSON、properties、manifest、asset 和 Android resource 不经过 JVM bytecode transformer；
* dependency JAR、generated output 或 `stringGuardPackages` 之外的 package，不会自动成为输入；其所属 module 必须应用 StrGuard；
* runtime 生成、Service 返回、JNI 加载，或已内联到另一个未受保护 module 的字符串，无法还原为源码 literal；
* 任意 `ConstantDynamic`、不支持的 `invokedynamic`、空字符串和超限值保持不变；
* `keepStringPackages` 与 `@KeepString` 是显式排除项。

`strictStringCoverage` 会在上述显式边界内审计每个选定 class。它只记录原因计数，不记录 literal 或位置；先写 `summary.txt`，再在发现非空 unsupported location 或未知 custom attribute 时失败，且不提交本轮 transformed class 或 Native input。它不能为从未进入 transformer 的 resource、dependency、排除 package 或 runtime-generated data 提供“全部已加密”的证明。

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

## Gradle Build Cache

StrGuard 的 transform、Desktop/Android Native build、Android merge 与 shrinker verification task 均支持 cache。输入不变时，第二次构建会是 `UP-TO-DATE`；执行 `clean`、切换 worktree 或进入兼容 CI worker 后，如启用 Build Cache，缺失 output 可直接以 `FROM-CACHE` 恢复：

```properties
# gradle.properties
org.gradle.caching=true
```

也可在命令行传入 `--build-cache`。Cache key 包含输入 class 内容与相对路径、seed fingerprint（不是 raw seed）、module identity、保护选项、target/ABI/minSdk、内置 runtime implementation、Rust toolchain selection，以及 Rust/NDK linker/archiver/library fingerprint。Native build 使用 task 私有 `CARGO_HOME` 与 `HOME`。任一输入变化都会重新执行，不会命中 stale cache。若项目或父目录提供 `.cargo/config` 或 `.cargo/config.toml`，任意 Cargo key 都可能选择额外且未跟踪的 tool，因此 StrGuard 会保守关闭 Native task 的 up-to-date 与 Build Cache 复用；transform cache 仍保持启用。

Raw release seed 不是 task input 或 output，也不会写入 cache entry。但是 transform entry 必然包含 seed-derived Native input、transformed class 与 vault data。因此本地和远程 Build Cache 都属于 protected artifact 的信任边界。禁止使用 public 或不可信 remote cache；应使用 authenticated TLS、按 trust domain 隔离，并尽量只允许可信 CI push，developer 只读。能够替换 cache entry 的主体也能够替换可执行构建输出。

## 安全与兼容

* 防护目标是普通 constant-pool scan、`strings` 与通用静态提取，不防止运行时受控代码读取。
* transformed classes、vault与 Native library属于同一构建单元，必须一起分发。
* deterministic task 支持 Gradle up-to-date check 与 Build Cache 恢复，并声明完整 normalized input；remote cache access 是上述安全边界的一部分。
* ChaCha20-Poly1305 authentication failure会失败退出。JNI返回 interned string以保持 Java/Kotlin literal identity。
* Cargo仅接收 allowlist环境，不接收 raw seed；输出有界，timeout/interruption会清理 descendant。
* derived byte array会清零；JVM `String`无法保证从 managed memory擦除。
* 输出先 staging，再作为一个可 rollback的 output set提交。
* Desktop loader 会枚举全部同名 classpath resource，只在同一 container 内配对 Native binary 与 marker，校验 artifact/bridge/loader metadata，并验证实际复制给 `System.load` 的同一份 bytes 的 SHA-256。
* 必须恰好存在一个有效 Native container。除非 `java.io.tmpdir/strguard` 与每个 `sg-*` extraction 都属于当前用户，且能强制 owner-only POSIX permission 或 owner-only ACL，否则 fail closed。
* Hash verification 与 `System.load` 在 generated loader 内连续完成后才 cleanup。启动时不扫描或删除其他 process 的 extraction；被 OS 锁定的文件安排在 JVM 退出时删除。
* OS temporary parent 仍是信任边界：可写 POSIX parent 必须具备 sticky bit；ACL filesystem 必须提供由当前用户拥有的 temp directory。同一 account 与 privileged local principal 不在 threat model 内。
* marker digest 能在 marker 可信时发现 binary 错配与篡改，但它不是 signature 或 MAC。能够同时替换 classpath marker 和 binary 的攻击者也已能控制应用代码，不在此 threat model 内。
* Java static final字符串与 Kotlin `const val`会失去 `ConstantValue`并在 `<clinit>`初始化，不要作为跨模块编译期常量或 annotation参数。
* annotation 字符串、任意 `ConstantDynamic`、不支持的 `invokedynamic`、空字符串和超过 30,000 个 UTF-16 code unit 的字符串保持不变。启用 strict coverage 后，非空 unsupported location 会让构建失败。
* metadata移除可能破坏 reflection、serialization和 compiler tooling。

安全漏洞请使用 GitHub private vulnerability reporting。不要在公开 Issue中提交 seed、key share、vault、Credential、exploit细节或私有应用代码。

## Report与迁移

每次 transform 都会写入 `build/reports/strguard/<target-or-variant>/summary.txt`，格式为 Java properties，`schemaVersion=1`。稳定字段包括 `enabled`、`strictStringCoverage`、`runtimeTarget`、class selection count、`stringCandidates`、`protectedStrings`、`skippedStrings`、`strictViolations`、`coverageUnknowns`、各原因的 `skipped*` count、`removedMetadata` 与 unmatched keep selector。满足 `stringCandidates = protectedStrings + skippedStrings`，且 `strictViolations` 还包含 `coverageUnknowns`。报告不包含 literal、seed、share、call-site identity 或 decoded value；consumer 必须忽略未知的 schema 1 可选字段。

版本 `2.0.0` 修改了 vault、Native runtime、task graph 与 shrinker 契约。所有受保护模块都必须 clean。当前 runtime 拒绝 vault v2。更改 seed、target、plugin 或 Native toolchain时应删除模块 `build/`。不要混用 1.x 或 pre-GA 2.0 class 与另一份 Native library。

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
