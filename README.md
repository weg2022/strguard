# StrGuard

StrGuard 2 is a Kotlin Gradle plugin that rewrites JVM class files with ASM. Eligible string literals become build-specific capability calls into a generated Rust JNI library backed by a ChaCha20-Poly1305 vault.

StrGuard raises the cost of static extraction. It is not a secret store: code that controls or instruments the running process can observe a string after it becomes a JVM `String`. Do not ship passwords, signing keys, or long-lived credentials in application code.

## Current scope

StrGuard 2 supports:

* Java, Java Library, Application, and Kotlin/JVM Gradle modules
* Android Application and Android Library variants with Kotlin or Java classes
* Kotlin Multiplatform JVM targets; JS, Native, and Wasm targets remain unchanged
* Windows x64, Linux x64, and macOS x64/arm64 Native runtime generation
* Android arm64-v8a Native runtime generation through the Android NDK
* Regular `LDC` literals and static final string constants
* Java 9+ `StringConcatFactory.makeConcatWithConstants` concatenation
* Kotlin/JVM string templates compiled to ordinary string operations
* Optional Kotlin metadata annotation removal
* Reproducible per-module output for the same seed, inputs, and toolchain

The supported plugin IDs are `java`, `java-library`, `application`, `org.jetbrains.kotlin.jvm`, `org.jetbrains.kotlin.android`, `com.android.application`, `com.android.library`, and `org.jetbrains.kotlin.multiplatform`. Kotlin Android must be paired with an Android Application or Library plugin. StrGuard only transforms KMP JVM targets and does not register work for JS, Native, or Wasm targets.

## Requirements

* JDK 17 or newer to run Gradle; CI tests JDK 17 and 21, while StrGuard and protected JVM output target Java 11
* Gradle 8.14.4 is the minimum and the version used by the wrapper; newer Gradle versions are not implied to be supported until tested
* Rust stable and Cargo with the selected target and its required linker
* Kotlin Gradle Plugin 2.1.21 is the tested Kotlin/JVM, Kotlin Android, and KMP version
* Android Gradle Plugin 8.13.2, Android SDK 34, and NDK 27.0.12077973 are the tested Android toolchain

Check the Native toolchain with:

```powershell
rustup target list --installed
cargo --version
```

## Installation

For a release approved on the Gradle Plugin Portal, apply the plugin to each JVM or Android module that owns the compiled classes:

```kotlin
plugins {
    id("io.github.weg2022.strguard") version "2.0.0"
}
```

Do not apply it only to an aggregator root project. StrGuard wires itself to a module's JVM compilation outputs or Android variants.

## Release seed

Every protected build requires a 256-bit seed encoded as exactly 64 hexadecimal characters. Inject it from CI rather than committing it:

```text
STRGUARD_RELEASE_SEED_HEX=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
```

The same seed, module identity, target, inputs, and toolchain produce the same transformed classes and Native inputs. Use different seeds for different trust domains. Never print or commit the seed.

## Configuration

```kotlin
strGuard {
    enabled.set(true)

    // Optional when STRGUARD_RELEASE_SEED_HEX is present.
    releaseSeedHex.set(providers.environmentVariable("STRGUARD_RELEASE_SEED_HEX"))

    // Desktop JVM/KMP only. Defaults to the current host.
    // STRGUARD_TARGET_TRIPLE can also override it.
    targetTriple.set("x86_64-pc-windows-msvc")

    // Empty means every application package except StrGuard support classes.
    stringGuardPackages.set(listOf("com.example.app"))
    keepStringPackages.set(listOf("com.example.app.generated"))

    java9StringConcatEnabled.set(true)
    consoleOutput.set(false)

    removeMetadata.set(false)
    removeMetadataPackages.set(listOf("com.example.app"))
    keepMetadataPackages.set(listOf("com.example.app.reflective"))
}
```

| Property | Default or convention | Effect |
| --- | --- | --- |
| `enabled` | `true` | Master switch. `false` disables rewriting, metadata removal, seed validation, and Native generation. |
| `releaseSeedHex` | `STRGUARD_RELEASE_SEED_HEX` | Required 256-bit release seed as exactly 64 hexadecimal characters when enabled. |
| `targetTriple` | `STRGUARD_TARGET_TRIPLE`, then current host | Desktop JVM/KMP Rust target. Android always selects its fixed target. |
| `java9StringConcatEnabled` | `true` | Protects supported Java 9+ `StringConcatFactory` recipe constants. |
| `consoleOutput` | `false` | Enables per-class transformation output. It never intentionally prints the seed or key material. |
| `removeMetadata` | `false` | Removes eligible Kotlin metadata annotations. |
| `stringGuardPackages` | Empty | Package prefixes eligible for string protection; empty means all non-StrGuard application classes. |
| `keepStringPackages` | Empty | Package prefixes excluded from string protection. |
| `removeMetadataPackages` | Empty | Package prefixes eligible for metadata removal; empty means all eligible classes. |
| `keepMetadataPackages` | Empty | Package prefixes excluded from metadata removal. |

Package entries accept dotted or slash-separated names and match that package plus its descendants. Keep lists take precedence over include lists.

Supported target triples are:

```text
x86_64-pc-windows-msvc
x86_64-unknown-linux-gnu
x86_64-apple-darwin
aarch64-apple-darwin
aarch64-linux-android
```

Android variants always use `aarch64-linux-android`; only `arm64-v8a` is supported and the Native runtime has an API 21 floor. Enabled builds fail during configuration when `minSdk` is below 21 or an ABI filter excludes `arm64-v8a`. The desktop `targetTriple` setting does not override Android variants. StrGuard adds application shrinker rules and library consumer rules so R8 preserves generated JNI bridge names and native methods.

`stringGuardPackages` is strongly recommended for production builds. An empty list deliberately means every non-StrGuard application class.

## Excluding a class

`KeepString` and `KeepMetadata` are supplied to Java and Kotlin compilation while the plugin is applied:

```kotlin
import io.github.weg2022.strguard.annotation.KeepString

@KeepString
class DiagnosticStrings {
    fun value() = "This literal stays in the class file"
}
```

`KeepMetadata` prevents metadata annotation removal for one class when `removeMetadata` is enabled.

## Build outputs

```text
build/strguard/classes/main
build/strguard/native-input/main
build/strguard/native-resources/main
build/reports/strguard/main
```

KMP JVM outputs add the target name, for example `build/strguard/classes/jvm/main`. Android outputs use the variant name, for example `build/strguard/native-input/debug` and `build/generated/strguard/jniLibs/debug`.

The standard JVM `jar`, runtime, and test classpaths use transformed classes and generated Native resources. A desktop JAR contains a randomized bridge, the Native loader, and a build-specific dynamic library. Android APKs store the build-specific library under `lib/arm64-v8a`; AARs use `jni/arm64-v8a`. Android bridges use `System.loadLibrary`, while desktop bridges extract their bundled library and use `System.load`. Runtime artifacts do not contain a JVM decoder or the compile-only `KeepString` and `KeepMetadata` annotation definitions.

## Security boundary

* StrGuard protects against straightforward constant-pool scans, `strings`, and generic static extraction. It does not protect a value after trusted code requests it at runtime.
* Each generated bridge, vault, and Native library is bound to one module build. Distribute transformed classes and their matching Native library together; never replace either independently.
* Transform and Native build tasks are excluded from Gradle Build Cache because their outputs contain build-specific seed-derived material. Normal Gradle up-to-date checks remain enabled.
* Treat `build/strguard/native-input`, transformed classes, generated Native libraries, reports, CI workspaces, filesystem snapshots, and backups as sensitive build material. Delete affected module `build/` directories before moving between trust domains or release seeds.
* Vault records are authenticated with ChaCha20-Poly1305. Authentication failure is fatal rather than returning unauthenticated text.
* Android packaging tests verify APK/AAR contents and R8 name retention. Runtime behavior on ART still requires an emulator, physical device, or device farm.

## Compatibility notes

* Static final Java string fields and Kotlin `const val` properties lose their `ConstantValue` attribute and are initialized in `<clinit>`. Do not expose protected values as cross-module compile-time constants or annotation arguments.
* Annotation string values, arbitrary `ConstantDynamic` data, and unsupported `invokedynamic` bootstraps remain unchanged.
* Empty and extremely large literals remain unchanged.
* Metadata removal can break Kotlin reflection, serialization libraries, and compiler tooling. Enable it only after integration testing.
* Every generated Native library is build-specific. Do not replace it independently from its transformed classes and vault.
* `enabled.set(false)` performs pass-through class packaging and does not validate a seed, target, Android API/ABI contract, Cargo, or NDK.

## Samples

The [`samples`](samples/README.md) directory contains independent builds for every supported project type: Java, Java Library, Application, Kotlin/JVM, Android Application, Android Library, Kotlin Android Application, and Kotlin Multiplatform.

## Development

```powershell
.\gradlew.bat :strguard-plugin:check :strguard-plugin:validatePlugins
```

Set `STRGUARD_ANDROID_NATIVE_TEST=true` and `ANDROID_NDK_VERSION=27.0.12077973` to make `KotlinAndroidPluginFunctionalTest` assemble an APK and compile the Android arm64 runtime instead of stopping after class transformation.

The test suite covers Java/Kotlin runtime decoding, JAR and Android packaging, multi-module R8 shrinking, disabled builds without Native toolchains, configuration-cache reuse, Build Cache isolation, desktop and Android target mapping, deterministic authenticated vault generation, Native key constant-folding checks, and static extraction regressions.

## CI and releases

`.github/workflows/ci.yml` only verifies the project. It checks JDK 17/21, Linux x64, Windows x64, macOS x64/arm64, and Android arm64-v8a packaging. It never publishes to the Plugin Portal.

Gradle Plugin Portal publication is a deliberate local maintainer operation. Put `gradle.publish.key` and `gradle.publish.secret` in Gradle User Home `gradle.properties`, verify the requested version, then run:

```powershell
.\gradlew.bat :strguard-plugin:check :strguard-plugin:validatePlugins --stacktrace
.\gradlew.bat :strguard-plugin:publishPlugins "-PstrguardVersion=2.0.0" --stacktrace
```

GitHub Actions never reads Portal credentials and never runs `publishPlugins`. Pushing a SemVer tag such as `v2.0.0` independently creates a GitHub Release with a generated title and notes. The tag workflow does not checkout source, run CI or Gradle, build artifacts, or upload files.

## License

Apache License 2.0. See [LICENSE](LICENSE).
