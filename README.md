# StrGuard

[English](README.md) | [简体中文](README.zh-CN.md)

StrGuard is a Kotlin Gradle plugin that rewrites JVM class files with ASM. Eligible string literals become build-specific capability calls into a generated Rust JNI library backed by a ChaCha20-Poly1305 vault.

StrGuard provides authenticated obfuscation that raises the cost of static extraction, not cryptographic secrecy after distribution. Each artifact necessarily contains the runtime material needed to decode its strings, and code that controls or instruments the process can observe a value after it becomes a JVM `String`. Do not ship passwords, signing keys, or long-lived credentials in application code.

## Scope

StrGuard supports:

* Java, Java Library, Application, and Kotlin/JVM modules
* Android Application and Android Library variants with Java or Kotlin classes
* Kotlin Multiplatform JVM and Android targets; JS, Native, and Wasm pass through
* Windows x64/arm64, Linux glibc x64/arm64, and macOS x64/arm64
* Android `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`
* ProGuard/R8 verified artifacts and Compose Desktop release ProGuard integration
* `LDC`, static final strings, Java 9+ `StringConcatFactory`, Kotlin string templates, and the same literals when used in arrays, collections, switches, lambdas, reflection calls, or other ordinary bytecode
* Optional Kotlin metadata annotation removal

Supported plugin IDs are `java`, `java-library`, `application`, `org.jetbrains.kotlin.jvm`, `org.jetbrains.kotlin.android`, `com.android.application`, `com.android.library`, and `org.jetbrains.kotlin.multiplatform`. Kotlin Android must be paired with an Android Application or Library plugin.

## Requirements

* JDK 17 or 21 to run Gradle; protected output requires Java 11 or the original bytecode version, whichever is newer
* ASM 9.10.1 accepts and preserves Java 11 through Java 27 class files (major 55-71), including Java 27 preview classes; inputs outside that range are unsupported
* The published plugin shades and relocates ASM into a private namespace, so an older ASM loaded by Gradle, `buildSrc`, or another plugin cannot downgrade class-file support
* Gradle 8.14.4
* Rust/Cargo 1.94.1 with the selected target and linker
* Kotlin Gradle Plugin 2.1.21
* Android Gradle Plugin 8.13.2, Android SDK 34, and NDK 27.2.12479018

`STRGUARD_CARGO_EXECUTABLE` may select a specific Cargo binary. Its sibling `rustc` and the selected linker/archiver are included in the toolchain fingerprint.

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
    strictStringCoverage.set(true)
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
| `strictStringCoverage` | `false` | Writes complete aggregate coverage and fails after scanning when a selected class has an unsupported non-empty string location or unknown custom attribute. |
| `consoleOutput` | `false` | Prints the schema-backed summary without literals or key material. |
| `removeMetadata` | `false` | Removes eligible Kotlin metadata annotations. |
| `stringGuardPackages` | Empty | Include prefixes; empty means all non-StrGuard application classes. |
| `keepStringPackages` | Empty | String-protection exclusion prefixes. |
| `removeMetadataPackages` | Empty | Metadata-removal include prefixes. |
| `keepMetadataPackages` | Empty | Metadata-removal exclusion prefixes. |

Package entries accept dotted or slash-separated names and include descendants. Keep lists take precedence.

## String coverage

Within selected application class files, StrGuard protects ordinary string constants regardless of the source syntax that produced them. This includes method literals, static final fields and Kotlin `const val`, array/collection elements, Java `switch` and Kotlin `when` branches, lambda bodies, reflection arguments, Java 9+ concat recipe fragments, and Kotlin templates. Values preserve their exact UTF-16 code units, including NUL, control characters, newlines, non-BMP characters, and unpaired surrogates. A protected value may contain at most 30,000 UTF-16 code units (60,000 UTF-16LE bytes).

StrGuard cannot transparently protect every string associated with an application:

* annotation values and defaults are part of class-file and framework contracts;
* resources such as XML, JSON, properties, manifests, assets, and Android resources are outside JVM bytecode transformation;
* dependency JARs, generated outputs, or packages outside `stringGuardPackages` are not inputs unless their owning module applies StrGuard;
* strings generated at runtime, received from a service, loaded through JNI, or already inlined into another unprotected module are not recoverable as source literals;
* arbitrary `ConstantDynamic`, unsupported `invokedynamic`, empty strings, and values over the size limit remain unchanged;
* `keepStringPackages` and `@KeepString` are intentional exclusions.

`strictStringCoverage` audits every selected class after applying those explicit boundaries. It aggregates reasons without recording literals or locations, writes `summary.txt`, and then fails before committing current transformed classes or Native inputs when a non-empty unsupported location or unknown custom attribute remains. It cannot claim coverage for resources, dependencies, excluded packages, or runtime-generated data that never entered the class transformer.

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

## Gradle Build Cache

StrGuard transform, Desktop/Android Native build, Android merge, and shrinker verification tasks are cacheable. With unchanged inputs, a second build is `UP-TO-DATE`. After `clean`, in a new worktree, or on another compatible CI worker, Gradle can restore missing outputs as `FROM-CACHE` when the Build Cache is enabled:

```properties
# gradle.properties
org.gradle.caching=true
```

Alternatively, pass `--build-cache`. Cache keys include input class contents and relative paths, the seed fingerprint (not the raw seed), module identity, protection options, target/ABI/minSdk, bundled runtime implementation, Rust toolchain selection, and Rust/NDK linker/archiver/library fingerprints. Native builds use task-private `CARGO_HOME` and `HOME`. Changing any of these inputs causes execution instead of a stale cache hit. If a project or ancestor supplies `.cargo/config` or `.cargo/config.toml`, StrGuard conservatively disables Native task up-to-date and Build Cache reuse because arbitrary Cargo keys can select additional untracked tools; transform caching remains enabled.

The raw release seed is not a task input or output and is not stored in cache entries. However, transform entries necessarily contain seed-derived Native inputs, transformed classes, and vault data. Treat every local or remote Build Cache as part of the protected artifact trust boundary. Never use a public or untrusted remote cache; require authenticated TLS, isolate trust domains, and allow only trusted CI builds to push while developers load read-only where practical. A party able to replace cache entries can replace executable build outputs.

## Security and compatibility

* StrGuard resists straightforward constant-pool scans, `strings`, and generic static extraction. It does not protect values after runtime access.
* Transformed classes, vault, and Native library are one build-specific unit and must be distributed together.
* Deterministic tasks support Gradle up-to-date checks and Build Cache restoration with complete normalized inputs. Remote cache access is a security boundary as described above.
* ChaCha20-Poly1305 authentication failure is fatal. JNI returns interned strings to preserve Java/Kotlin literal identity.
* Cargo receives an allowlisted environment without the raw seed. Output is bounded and timeout/interruption terminates descendants.
* Derived byte arrays are cleared. JVM `String` values cannot be reliably erased from managed memory.
* Outputs are staged and committed as one rollback-capable set.
* Desktop loading enumerates all matching classpath resources, pairs the Native binary and marker only within the same container, validates artifact/bridge/loader metadata, and verifies the SHA-256 of the exact bytes copied for `System.load`.
* Exactly one valid Native container is required. The loader fails closed unless `java.io.tmpdir/strguard` and each `sg-*` extraction are owned by the current user and protected by owner-only POSIX permissions or an owner-only ACL.
* Hash verification and `System.load` run inside the generated loader before cleanup. Startup never scans or deletes another process's extraction; locked files are scheduled for deletion at JVM exit.
* The OS temporary parent remains a trust boundary: writable POSIX parents must have the sticky bit, and ACL filesystems must expose a current-user-owned temp directory. Same-account and privileged local principals are outside the threat model.
* The marker digest detects mismatches and binary tampering while the marker is trusted; it is not a signature or MAC. An attacker who can replace both the marker and binary on the classpath still controls application code and remains outside this threat model.
* Static final strings and Kotlin `const val` lose `ConstantValue` and initialize in `<clinit>`. Do not use protected values as cross-module compile-time constants or annotation arguments.
* Annotation strings, arbitrary `ConstantDynamic`, unsupported `invokedynamic`, empty strings, and strings over 30,000 UTF-16 code units remain unchanged. Enable strict coverage to make non-empty unsupported locations fail the build.
* Metadata removal can break reflection, serialization, and compiler tooling.

Report vulnerabilities through GitHub private vulnerability reporting. Never post a release seed, key share, protected vault, credential, exploit detail, or private application code in a public issue.

## Reports and migration

Each transform writes `build/reports/strguard/<target-or-variant>/summary.txt` as Java properties with `schemaVersion=1`. Stable fields include `enabled`, `strictStringCoverage`, `runtimeTarget`, class-selection counts, `stringCandidates`, `protectedStrings`, `skippedStrings`, `strictViolations`, `coverageUnknowns`, per-reason `skipped*` counts, `removedMetadata`, and unmatched keep selectors. `stringCandidates = protectedStrings + skippedStrings`; `strictViolations` also includes `coverageUnknowns`. Reports never contain literals, seeds, shares, call-site identities, or decoded values. Consumers must ignore unknown optional schema 1 fields.

Version `2.0.0` changes the vault, Native runtime, task graph, and shrinker contract. Clean every protected module. Vault v2 is rejected. Delete module `build/` directories when changing the seed, target, plugin, or Native toolchain. Never combine 1.x or pre-GA 2.0 classes with another generated Native library.

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
