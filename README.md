# StrGuard

StrGuard 2 is a Kotlin Gradle plugin that rewrites Java and Kotlin/JVM class files with ASM. Eligible string literals become build-specific capability calls into a generated Rust JNI library backed by a ChaCha20-Poly1305 vault.

StrGuard raises the cost of static extraction. It is not a secret store: code that controls or instruments the running process can observe a string after it becomes a JVM `String`. Do not ship passwords, signing keys, or long-lived credentials in application code.

See [the StrGuard 2 threat model](docs/strguard-2-threat-model.md) for the security boundary and vault format.

## Current scope

The current 2.0 milestone supports:

* Java and Kotlin/JVM Gradle modules
* Windows x64, Linux x64, and macOS x64/arm64 Native runtime generation
* Regular `LDC` literals and static final string constants
* Java 9+ `StringConcatFactory.makeConcatWithConstants` concatenation
* Kotlin/JVM string templates compiled to ordinary string operations
* Optional Kotlin metadata annotation removal
* Reproducible per-module output for the same seed, inputs, and toolchain

Windows x64 is covered by a runtime JNI test in the current development environment. Linux and macOS mappings are unit tested but still require their corresponding host/linker CI jobs. Android application/library integration and Android arm64 remain follow-up milestones. The plugin currently rejects Android and Kotlin Multiplatform modules instead of silently producing an incomplete artifact.

## Requirements

* JDK 17 or newer
* Gradle 8.14 or newer
* Rust and Cargo with the selected target and its required linker
* A Java or Kotlin/JVM module

Check the Native toolchain with:

```powershell
rustup target list --installed
cargo --version
```

## Installation

Apply the plugin to the JVM module that owns the compiled classes:

```kotlin
plugins {
    id("io.github.weg2022.strguard") version "2.0.0-SNAPSHOT"
}
```

Do not apply it only to an aggregator root project. StrGuard wires itself to a module's Java and Kotlin/JVM compilation outputs.

## Release seed

Every protected build requires a 256-bit seed encoded as exactly 64 hexadecimal characters. Inject it from CI rather than committing it:

```text
STRGUARD_RELEASE_SEED_HEX=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
```

The same seed, module identity, target, inputs, and toolchain produce the same transformed classes and Native inputs. Use different seeds for different trust domains. Treat `build/strguard/native-input` as a sensitive build intermediate because it contains encoded Native key material.

## Configuration

```kotlin
strGuard {
    enabled.set(true)

    // Optional when STRGUARD_RELEASE_SEED_HEX is present.
    releaseSeedHex.set(providers.environmentVariable("STRGUARD_RELEASE_SEED_HEX"))

    // Defaults to the current desktop host. STRGUARD_TARGET_TRIPLE can also override it.
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

Supported target triples are:

```text
x86_64-pc-windows-msvc
x86_64-unknown-linux-gnu
x86_64-apple-darwin
aarch64-apple-darwin
```

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

The standard `jar`, runtime, and test classpaths use transformed classes and generated Native resources. The final JAR contains a randomized bridge, the Native loader, annotations, and a build-specific dynamic library. It does not contain the former JVM `StrGuardRuntime` decoder.

## Compatibility notes

* Static final Java string fields and Kotlin `const val` properties lose their `ConstantValue` attribute and are initialized in `<clinit>`. Do not expose protected values as cross-module compile-time constants or annotation arguments.
* Annotation string values, arbitrary `ConstantDynamic` data, and unsupported `invokedynamic` bootstraps remain unchanged.
* Empty and extremely large literals remain unchanged.
* Metadata removal can break Kotlin reflection, serialization libraries, and compiler tooling. Enable it only after integration testing.
* The generated DLL is build-specific. Do not replace it independently from its transformed classes.

## Development

```powershell
.\gradlew.bat :strguard-plugin:check :strguard-plugin:validatePlugins
```

The test suite covers Java/Kotlin runtime decoding, JAR packaging, configuration-cache reuse, desktop target mapping, deterministic and authenticated vault generation, Native key constant-folding checks, and a static attack harness that recovers the 1.x XOR format.

## License

Apache License 2.0. See [LICENSE](LICENSE).
