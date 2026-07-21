# StrGuard

[English](README.md) | [简体中文](README.zh-CN.md)

StrGuard 2 is a Kotlin Gradle plugin that rewrites JVM class files with ASM. Eligible string literals become build-specific capability calls into a generated Rust JNI library backed by a ChaCha20-Poly1305 vault.

StrGuard raises the cost of static extraction. It is not a secret store: code that controls or instruments the running process can observe a value after it becomes a JVM `String`. Do not ship passwords, signing keys, or long-lived credentials in application code.

## Scope

StrGuard 2 supports:

* Java, Java Library, Application, and Kotlin/JVM modules
* Android Application and Android Library variants with Java or Kotlin classes
* Kotlin Multiplatform JVM and Android targets; JS, Native, and Wasm pass through
* Windows x64/arm64, Linux glibc x64/arm64, and macOS x64/arm64
* Android `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`
* ProGuard/R8 verified artifacts and Compose Desktop release ProGuard integration
* `LDC`, static final strings, Java 9+ `StringConcatFactory`, and Kotlin string templates
* Optional Kotlin metadata annotation removal

Supported plugin IDs are `java`, `java-library`, `application`, `org.jetbrains.kotlin.jvm`, `org.jetbrains.kotlin.android`, `com.android.application`, `com.android.library`, and `org.jetbrains.kotlin.multiplatform`. Kotlin Android must be paired with an Android Application or Library plugin.

## Requirements

* JDK 17 or 21 to run Gradle; protected output requires Java 11 or the original bytecode version, whichever is newer
* Gradle 8.14.4
* Rust/Cargo 1.94.1 with the selected target and linker
* Kotlin Gradle Plugin 2.1.21
* Android Gradle Plugin 8.13.2, Android SDK 34, and NDK 27.2.12479018

`STRGUARD_CARGO_EXECUTABLE` may select a specific Cargo binary. Its sibling `rustc` and the selected linker are included in the toolchain fingerprint.

## Installation

Until `2.0.0` is published and passes the external Plugin Portal smoke gate, use this repository as a composite build. After GA is visible on the Portal, apply StrGuard to every JVM or Android module that owns compiled classes:

```kotlin
plugins {
    id("io.github.weg2022.strguard") version "2.0.0"
}
```

Do not apply it only to an aggregator root project.

## Release seed

Every enabled build requires a 256-bit seed encoded as exactly 64 hexadecimal characters. Inject it from CI and never print or commit it:

```text
STRGUARD_RELEASE_SEED_HEX=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
```

The same seed, module identity, target, inputs, and toolchain produce the same transformed classes and Native inputs. Use different seeds for different trust domains.

## Configuration

```kotlin
strGuard {
    enabled.set(true)
    releaseSeedHex.set(providers.environmentVariable("STRGUARD_RELEASE_SEED_HEX"))

    // Desktop JVM/KMP only. Defaults to the current host.
    targetTriple.set("x86_64-pc-windows-msvc")

    // Android only. Defaults to all four official ABIs.
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

| Property | Default | Effect |
| --- | --- | --- |
| `enabled` | `true` | Disables rewriting, seed/target/API/ABI validation, and Native work when false. |
| `releaseSeedHex` | `STRGUARD_RELEASE_SEED_HEX` | Required 64-character hexadecimal seed when enabled. |
| `targetTriple` | Environment or current host | Desktop JVM/KMP Rust target; one runtime per JAR. |
| `androidAbis` | Four official ABIs | Non-empty Android ABI set; AGP filters and final splits must be subsets. |
| `java9StringConcatEnabled` | `true` | Protects supported Java 9+ concat recipe constants. |
| `consoleOutput` | `false` | Prints the schema-backed summary without literals or key material. |
| `removeMetadata` | `false` | Removes eligible Kotlin metadata annotations. |
| `stringGuardPackages` | Empty | Include prefixes; empty means all non-StrGuard application classes. |
| `keepStringPackages` | Empty | String-protection exclusion prefixes. |
| `removeMetadataPackages` | Empty | Metadata-removal include prefixes. |
| `keepMetadataPackages` | Empty | Metadata-removal exclusion prefixes. |

Package entries accept dotted or slash-separated names and include descendants. Keep lists take precedence.

Supported Desktop targets:

```text
x86_64-pc-windows-msvc
aarch64-pc-windows-msvc
x86_64-unknown-linux-gnu
aarch64-unknown-linux-gnu
x86_64-apple-darwin
aarch64-apple-darwin
```

Android uses ABI-neutral transformed classes and vault data, then builds the configured Rust targets. The Native runtime has an API 21 floor. Builds fail when `minSdk < 21` or an AGP ABI filter/final split is absent from `androidAbis`. AAR markers declare the exact ABI set and minSdk.

## Excluding a class

`KeepString` and `KeepMetadata` are available during Java and Kotlin compilation:

```kotlin
import io.github.weg2022.strguard.annotation.KeepString

@KeepString
class DiagnosticStrings {
    fun value() = "This literal stays in the class file"
}
```

## Outputs and shrinkers

```text
build/strguard/classes/main
build/strguard/native-input/main
build/strguard/native-resources/main
build/reports/strguard/main
```

KMP JVM outputs include the target name. Android outputs use the variant name. JVM JARs contain a randomized bridge, module-unique loader, one target Native library, mandatory shrinker rules, and a versioned marker. Android APKs use `lib/<abi>` and AARs use `jni/<abi>`.

Desktop shrinkers must run in this order:

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

Only publish/distribute the verified output. Applications still own rules for public API, reflection, serialization, `ServiceLoader`, main classes, Kotlin metadata, and frameworks. Compose Desktop release ProGuard is wired automatically without changing user shrinker settings.

## Security and compatibility

* StrGuard resists straightforward constant-pool scans, `strings`, and generic static extraction. It does not protect values after runtime access.
* Transformed classes, vault, and Native library are one build-specific unit and must be distributed together.
* Sensitive tasks cannot be opted into Gradle Build Cache. Normal up-to-date checks remain enabled.
* ChaCha20-Poly1305 authentication failure is fatal. JNI returns interned strings to preserve Java/Kotlin literal identity.
* Cargo receives an allowlisted environment without the raw seed. Output is bounded and timeout/interruption terminates descendants.
* Derived byte arrays are cleared. JVM `String` values cannot be reliably erased from managed memory.
* Outputs are staged and committed as one rollback-capable set.
* Desktop extraction only handles generated names under `java.io.tmpdir/strguard/sg2-*`; unknown files are never recursively deleted.
* Static final strings and Kotlin `const val` lose `ConstantValue` and initialize in `<clinit>`. Do not use protected values as cross-module compile-time constants or annotation arguments.
* Annotation strings, arbitrary `ConstantDynamic`, unsupported `invokedynamic`, empty strings, and extremely large literals remain unchanged.
* Metadata removal can break reflection, serialization, and compiler tooling.

Report vulnerabilities through GitHub private vulnerability reporting. Never post a release seed, key share, protected vault, credential, exploit detail, or private application code in a public issue.

## Reports and migration

Each transform writes `build/reports/strguard/<target-or-variant>/summary.txt` as Java properties with `schemaVersion=1`. Stable fields are `enabled`, `runtimeTarget`, `inputClasses`, `eligibleClasses`, `matchedClasses`, `skippedClasses`, `protectedStrings`, `removedMetadata`, `unmatchedKeepStringPackages`, and `unmatchedKeepMetadataPackages`. Reports never contain literals, seeds, shares, or decoded values. Consumers must ignore unknown optional schema 1 fields.

StrGuard 2 changes the vault, Native runtime, task graph, and shrinker contract. Clean every protected module. Vault v2 is rejected. Delete module `build/` directories when changing the seed, target, plugin, or Native toolchain. Never combine 1.x or pre-GA 2.0 classes with another generated Native library.

## Development

The [`samples`](samples/README.md) directory covers every supported project type.

```powershell
.\gradlew.bat :strguard-plugin:check :strguard-plugin:validatePlugins
cargo fmt --manifest-path native/strguard-runtime/Cargo.toml -- --check
cargo clippy --manifest-path native/strguard-runtime/Cargo.toml --all-targets --locked -- -D warnings
cargo test --manifest-path native/strguard-runtime/Cargo.toml --locked
```

Set `STRGUARD_ANDROID_NATIVE_TEST=true` and `ANDROID_NDK_VERSION=27.2.12479018` to compile all four Android runtimes in functional tests. Local tests do not start an emulator; ART evidence comes from Firebase Test Lab.

## CI and release

`ci.yml` covers JDK 17/21, Java 11, six Desktop runners, four-ABI Android/R8, Rust audit/deny/coverage/performance/RSS, reproducibility, dependency verification, distribution, and samples. `android-device-farm.yml` validates four actual ART process ABIs and runs normal and tampered-vault APKs.

`release.yml` is a protected RC/GA workflow. GA requires the same RC commit to be observed for seven days with no open P0/P1 issues. It then re-runs assurance gates, tests external Portal JVM/Android consumers, exports an SPDX SBOM, writes checksums, and creates the immutable tag and GitHub Release.

The repository must configure a protected `release` environment, Portal credentials, Google Workload Identity, the Test Lab project, and a four-entry `FIREBASE_DEVICE_MATRIX_JSON` containing API 21 and API 34+ coverage.

## License

Apache License 2.0. See [LICENSE](LICENSE).
