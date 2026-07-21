# StrGuard samples

Each directory is an independent Gradle build that loads the StrGuard plugin from this repository through `includeBuild`. The samples cover every supported Gradle project and plugin combination.

| Sample | Supported plugins demonstrated |
| --- | --- |
| `java` | `java` |
| `java-library` | `java-library` |
| `application` | `application` |
| `kotlin-jvm` | `org.jetbrains.kotlin.jvm` |
| `android-application` | `com.android.application` with Java sources |
| `android-library` | `com.android.library` with Java sources |
| `kotlin-android-application` | `com.android.application` and `org.jetbrains.kotlin.android` |
| `kotlin-multiplatform` | `org.jetbrains.kotlin.multiplatform` with protected JVM and unchanged JS targets |

Protected builds require a private 64-character hexadecimal seed. Set it in the environment rather than adding it to a build script:

```powershell
$env:STRGUARD_RELEASE_SEED_HEX = "<64 hexadecimal characters>"
```

Run desktop samples from the repository root with the wrapper, for example:

```powershell
.\gradlew.bat -p samples/application run
.\gradlew.bat -p samples/java build
.\gradlew.bat -p samples/java-library build
.\gradlew.bat -p samples/kotlin-jvm run
.\gradlew.bat -p samples/kotlin-multiplatform jvmJar
```

Desktop builds require Rust 1.94.1 and the target selected for the current host. Android builds additionally require Android SDK 34, NDK `27.2.12479018`, and all configured Rust Android targets:

```powershell
rustup target add armv7-linux-androideabi aarch64-linux-android i686-linux-android x86_64-linux-android
.\gradlew.bat -p samples/android-application assembleDebug
.\gradlew.bat -p samples/android-library assembleDebug
.\gradlew.bat -p samples/kotlin-android-application assembleDebug
```

The Kotlin Android sample packages all four official ABIs and includes an AndroidX instrumentation test for JNI loading, literal identity, and UTF-16. Run it through the Firebase device-farm workflow; local sample commands do not start an emulator. All Android samples use `minSdk = 21`.
