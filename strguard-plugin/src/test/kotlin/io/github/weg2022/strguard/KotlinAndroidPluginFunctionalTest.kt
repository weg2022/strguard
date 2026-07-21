package io.github.weg2022.strguard

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.zip.ZipFile
import kotlin.test.*

class KotlinAndroidPluginFunctionalTest {
    @TempDir
    lateinit var projectDirectory: Path

    @Test
    fun `protects Kotlin Android application classes`() {
        val runtimeTestEnabled =
            System.getenv("STRGUARD_ANDROID_RUNTIME_TEST").equals("true", ignoreCase = true)
        val nativeBuildEnabled =
            runtimeTestEnabled ||
                System.getenv("STRGUARD_ANDROID_NATIVE_TEST").equals("true", ignoreCase = true)
        val ndkVersion = System.getenv("ANDROID_NDK_VERSION")
        if (nativeBuildEnabled) {
            assumeTrue(!ndkVersion.isNullOrBlank(), "ANDROID_NDK_VERSION is required for Native integration testing")
        }
        val sdkDirectory = findKotlinAndroidSdk()
        assumeTrue(sdkDirectory != null, "Android SDK is not available for AGP integration testing")
        val availableSdk = requireNotNull(sdkDirectory)
        writeFile(
            "settings.gradle.kts",
            """
            pluginManagement {
                includeBuild("${projectRootPath()}")
                repositories {
                    google()
                    gradlePluginPortal()
                    mavenCentral()
                }
            }
            rootProject.name = "kotlin-android-consumer"
            """.trimIndent(),
        )
        writeFile(
            "local.properties",
            "sdk.dir=${availableSdk.toString().replace("\\", "\\\\")}",
        )
        writeFile(
            "gradle.properties",
            buildString {
                appendLine("android.useAndroidX=true")
                if (nativeBuildEnabled) appendLine("android.builder.sdkDownload=true")
            },
        )
        writeFile(
            "build.gradle.kts",
            """
            import org.jetbrains.kotlin.gradle.dsl.JvmTarget

            plugins {
                id("com.android.application") version "8.13.2"
                id("org.jetbrains.kotlin.android") version "2.1.21"
                id("io.github.weg2022.strguard")
            }

            repositories {
                google()
                mavenCentral()
            }

            android {
                namespace = "sample.android"
                compileSdk = 34
                ${ndkVersion?.let { "ndkVersion = \"$it\"" }.orEmpty()}

                defaultConfig {
                    applicationId = "sample.android"
                    minSdk = 21
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_11
                    targetCompatibility = JavaVersion.VERSION_11
                }
            }

            kotlin {
                compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
            }

            dependencies {
                androidTestImplementation("androidx.test.ext:junit:1.2.1")
                androidTestImplementation("androidx.test:runner:1.6.2")
            }

            strGuard {
                releaseSeedHex.set("$KOTLIN_ANDROID_TEST_SEED")
                stringGuardPackages.set(listOf("sample"))
                consoleOutput.set(true)
            }
            """.trimIndent(),
        )
        writeFile(
            "src/main/AndroidManifest.xml",
            "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" />",
        )
        writeFile(
            "src/main/kotlin/sample/android/KotlinAndroidExample.kt",
            """
            package sample.android

            class KotlinAndroidExample {
                fun reveal(value: String): String = "android-prefix-${'$'}value-sensitive-suffix"

                fun identityFirst(): String = "android-shared-identity"

                fun identitySecond(): String = "android-shared-identity"

                fun specialUtf16(): String = "prefix\u0000\uD800middle\uDC00\uD83D\uDE00suffix"
            }
            """.trimIndent(),
        )
        writeFile(
            "src/androidTest/kotlin/sample/android/InstrumentationProbe.kt",
            """
            package sample.android

            import android.os.Build
            import androidx.test.ext.junit.runners.AndroidJUnit4
            import org.junit.Assert.assertArrayEquals
            import org.junit.Assert.assertEquals
            import org.junit.Assert.assertSame
            import org.junit.Assert.assertTrue
            import org.junit.Test
            import org.junit.runner.RunWith

            @RunWith(AndroidJUnit4::class)
            class InstrumentationProbe {
                @Test
                fun protectedRuntimeLoadsOnArt() {
                    val example = KotlinAndroidExample()
                    assertTrue(Build.SUPPORTED_ABIS.isNotEmpty())
                    assertEquals(
                        "android-prefix-instrumentation-sensitive-suffix",
                        example.reveal("instrumentation"),
                    )
                    assertSame(example.identityFirst(), example.identitySecond())
                    assertArrayEquals(
                        "prefix\u0000\uD800middle\uDC00\uD83D\uDE00suffix".toCharArray(),
                        example.specialUtf16().toCharArray(),
                    )
                }
            }
            """.trimIndent(),
        )

        val result = if (runtimeTestEnabled) {
            runner("connectedDebugAndroidTest").build()
        } else if (nativeBuildEnabled) {
            runner("assembleDebug", "assembleDebugAndroidTest").build()
        } else {
            runner("transformStrGuardDebugClasses").build()
        }

        assertEquals(TaskOutcome.SUCCESS, result.task(":transformStrGuardDebugClasses")?.outcome)
        assertTrue(result.output.contains("protected"))
        val transformedJar = findTransformedJar("sample/android/KotlinAndroidExample.class")
        JarFile(transformedJar.toFile()).use { jar ->
            val classEntry = assertNotNull(jar.getJarEntry("sample/android/KotlinAndroidExample.class"))
            val classText = jar.getInputStream(classEntry).readBytes().toString(StandardCharsets.ISO_8859_1)
            assertFalse(classText.contains("sensitive-suffix"))
            assertTrue(
                jar.entries().asSequence().any {
                    it.name.startsWith("io/github/weg2022/strguard/generated/B") && it.name.endsWith(".class")
                },
            )
            assertTrue(jar.getJarEntry("io/github/weg2022/strguard/runtime/NativeLibraryLoader.class") == null)
        }
        assertTrue(
            Files.isRegularFile(projectDirectory.resolve("build/strguard/native-input/debug/vault.bin")),
        )
        if (nativeBuildEnabled) {
            assertEquals(TaskOutcome.SUCCESS, result.task(":buildStrGuardDebugNative")?.outcome)
            AndroidAbi.entries.forEach { abi ->
                assertEquals(
                    TaskOutcome.SUCCESS,
                    result.task(":buildStrGuardDebug${abi.taskSuffix}Native")?.outcome,
                )
            }
            verifyNativeApk()
            verifyInstrumentationApk()
            if (runtimeTestEnabled) {
                assertEquals(TaskOutcome.SUCCESS, result.task(":connectedDebugAndroidTest")?.outcome)
            }
        }
    }

    private fun findTransformedJar(requiredEntry: String): Path = Files.walk(projectDirectory.resolve("build")).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
            .filter { candidate ->
                runCatching {
                    JarFile(candidate.toFile()).use { jar -> jar.getJarEntry(requiredEntry) != null }
                }.getOrDefault(false)
            }
            .findFirst()
            .orElseThrow { AssertionError("No transformed Android classes JAR contains $requiredEntry") }
    }

    private fun verifyNativeApk() {
        val apk = Files.walk(projectDirectory.resolve("build/outputs/apk")).use { paths ->
            paths.filter {
                Files.isRegularFile(it) &&
                    it.fileName.toString().endsWith(".apk") &&
                    !it.toString().replace('\\', '/').contains("/androidTest/")
            }
                .findFirst()
                .orElseThrow { AssertionError("Android build did not produce an APK") }
        }
        ZipFile(apk.toFile()).use { archive ->
            val nativeEntries =
                archive.entries().asSequence().filter { entry ->
                    AndroidAbi.entries.any { abi ->
                        entry.name.startsWith("lib/${abi.abiName}/libsg_") && entry.name.endsWith(".so")
                    }
                }.toList()
            assertEquals(AndroidAbi.entries.size, nativeEntries.size)
            assertEquals(
                AndroidAbi.entries.map(AndroidAbi::abiName).toSet(),
                nativeEntries.map { entry -> entry.name.substringAfter("lib/").substringBefore('/') }.toSet(),
            )
            nativeEntries.forEach { nativeEntry ->
                val nativeText =
                    archive.getInputStream(nativeEntry).readBytes().toString(StandardCharsets.ISO_8859_1)
                assertFalse(nativeText.contains("sensitive-suffix"))
                assertFalse(nativeText.contains(KOTLIN_ANDROID_TEST_SEED))
            }

            val dexEntries = archive.entries().asSequence().filter {
                it.name.startsWith("classes") && it.name.endsWith(".dex")
            }.toList()
            assertTrue(dexEntries.isNotEmpty(), "APK does not contain DEX bytecode")
            assertTrue(
                dexEntries.none { entry ->
                    archive.getInputStream(entry).readBytes().toString(StandardCharsets.ISO_8859_1)
                        .contains("sensitive-suffix")
                },
            )
        }
    }

    private fun verifyInstrumentationApk() {
        val apk = Files.walk(projectDirectory.resolve("build/outputs/apk/androidTest")).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".apk") }
                .findFirst()
                .orElseThrow { AssertionError("Android build did not produce an instrumentation APK") }
        }
        ZipFile(apk.toFile()).use { archive ->
            assertTrue(
                archive.entries().asSequence().any {
                    it.name.startsWith("classes") && it.name.endsWith(".dex")
                },
                "Instrumentation APK does not contain DEX bytecode",
            )
        }
    }

    private fun runner(vararg arguments: String): GradleRunner = GradleRunner.create()
        .withProjectDir(projectDirectory.toFile())
        .withArguments(*arguments, "--stacktrace")
        .forwardOutput()

    private fun writeFile(relativePath: String, contents: String) {
        val file = projectDirectory.resolve(relativePath)
        Files.createDirectories(file.parent)
        Files.writeString(file, contents, StandardCharsets.UTF_8)
    }

    private fun projectRootPath(): String = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().toString().replace("\\", "\\\\")
}

private fun findKotlinAndroidSdk(): Path? {
    val configured =
        sequenceOf(
            System.getProperty("android.sdk.path"),
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
        ).filterNotNull().map(Path::of)
    val conventional =
        sequenceOf(
            Path.of(System.getProperty("user.home"), "AppData", "Local", "Android", "Sdk"),
            Path.of(System.getProperty("user.home"), "Android", "Sdk"),
        )
    return (configured + conventional)
        .firstOrNull { Files.isRegularFile(it.resolve("platforms/android-34/android.jar")) }
}

private const val KOTLIN_ANDROID_TEST_SEED =
    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
