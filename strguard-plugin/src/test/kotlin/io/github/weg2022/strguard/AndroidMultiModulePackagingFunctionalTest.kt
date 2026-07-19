package io.github.weg2022.strguard

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarInputStream
import java.util.zip.ZipFile
import kotlin.test.*

class AndroidMultiModulePackagingFunctionalTest {
    @TempDir
    lateinit var projectDirectory: Path

    @Test
    fun `packages protected Android application and library without duplicate support classes`() {
        val sdkDirectory = findAndroidPackagingSdk()
        assumeTrue(sdkDirectory != null, "Android SDK is not available for multi-module testing")
        val nativeBuildEnabled =
            System.getenv("STRGUARD_ANDROID_NATIVE_TEST").equals("true", ignoreCase = true)
        val ndkVersion = System.getenv("ANDROID_NDK_VERSION")
        if (nativeBuildEnabled) {
            assumeTrue(!ndkVersion.isNullOrBlank(), "ANDROID_NDK_VERSION is required for Native integration testing")
        }
        writeProject(requireNotNull(sdkDirectory), nativeBuildEnabled, ndkVersion, minifiedRelease = false)

        val result = runner(":library:assembleDebug", ":app:assembleDebug").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":library:transformStrGuardDebugClasses")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":app:transformStrGuardDebugClasses")?.outcome)
        verifyLibraryAar(nativeBuildEnabled)
        verifyApplicationApk(nativeBuildEnabled)
    }

    @Test
    fun `keeps generated JNI bridge names in minified Android application`() {
        val sdkDirectory = findAndroidPackagingSdk()
        assumeTrue(sdkDirectory != null, "Android SDK is not available for R8 integration testing")
        writeProject(
            requireNotNull(sdkDirectory),
            nativeBuildEnabled = false,
            ndkVersion = null,
            minifiedRelease = true,
        )

        val result = runner(":app:assembleRelease").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":library:transformStrGuardReleaseClasses")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":app:transformStrGuardReleaseClasses")?.outcome)
        verifyApplicationApk(nativeBuildEnabled = false)
        verifyR8BridgeNames()
    }

    private fun writeProject(
        sdkDirectory: Path,
        nativeBuildEnabled: Boolean,
        ndkVersion: String?,
        minifiedRelease: Boolean,
    ) {
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
            dependencyResolutionManagement {
                repositories {
                    google()
                    mavenCentral()
                }
            }
            rootProject.name = "android-multi-module"
            include(":app", ":library")
            """.trimIndent(),
        )
        writeFile("local.properties", "sdk.dir=${sdkDirectory.toString().replace("\\", "\\\\")}")
        writeFile("build.gradle.kts", "plugins { base }")
        writeFile(
            "library/build.gradle.kts",
            androidBuildScript(
                pluginId = "com.android.library",
                namespace = "sample.library",
                nativeBuildEnabled = nativeBuildEnabled,
                ndkVersion = ndkVersion,
                minifiedRelease = false,
            ),
        )
        writeFile(
            "app/build.gradle.kts",
            androidBuildScript(
                pluginId = "com.android.application",
                namespace = "sample.app",
                nativeBuildEnabled = nativeBuildEnabled,
                ndkVersion = ndkVersion,
                minifiedRelease = minifiedRelease,
                extraConfiguration =
                    """
                    dependencies {
                        implementation(project(":library"))
                    }
                    """.trimIndent(),
            ),
        )
        writeFile(
            "library/src/main/AndroidManifest.xml",
            "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" />",
        )
        writeFile(
            "app/src/main/AndroidManifest.xml",
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:name=".ProtectedApplication" />
            </manifest>
            """.trimIndent(),
        )
        writeFile(
            "library/src/main/java/sample/library/ProtectedLibrary.java",
            """
            package sample.library;

            public final class ProtectedLibrary {
                public static String reveal() {
                    return "android-library-sensitive-value";
                }
            }
            """.trimIndent(),
        )
        writeFile(
            "app/src/main/java/sample/app/ProtectedApplication.java",
            """
            package sample.app;

            import sample.library.ProtectedLibrary;

            public final class ProtectedApplication extends android.app.Application {
                @Override
                public void onCreate() {
                    super.onCreate();
                    if (reveal().isEmpty()) {
                        throw new IllegalStateException("protected value is empty");
                    }
                }

                public static String reveal() {
                    return "android-app-sensitive-value|" + ProtectedLibrary.reveal();
                }
            }
            """.trimIndent(),
        )
    }

    private fun androidBuildScript(
        pluginId: String,
        namespace: String,
        nativeBuildEnabled: Boolean,
        ndkVersion: String?,
        minifiedRelease: Boolean,
        extraConfiguration: String = "",
    ): String =
        """
        plugins {
            id("$pluginId") version "8.13.2"
            id("io.github.weg2022.strguard")
        }

        android {
            namespace = "$namespace"
            compileSdk = 34
            ${ndkVersion?.let { "ndkVersion = \"$it\"" }.orEmpty()}

            defaultConfig {
                minSdk = 21
                ${if (pluginId == "com.android.application") "applicationId = \"$namespace\"" else ""}
            }

            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_11
                targetCompatibility = JavaVersion.VERSION_11
            }

            ${
            if (minifiedRelease) """
            buildTypes {
                release {
                    isMinifyEnabled = true
                    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
                }
            }
            """.trimIndent() else ""
        }
        }

        strGuard {
            releaseSeedHex.set("$ANDROID_MULTI_MODULE_TEST_SEED")
            stringGuardPackages.set(listOf("sample"))
        }

        ${if (nativeBuildEnabled) "" else "tasks.matching { it.name.startsWith(\"buildStrGuard\") && it.name.endsWith(\"Native\") }.configureEach { enabled = false }"}

        $extraConfiguration
        """.trimIndent()

    private fun verifyLibraryAar(nativeBuildEnabled: Boolean) {
        val aar = findArtifact(projectDirectory.resolve("library/build/outputs/aar"), ".aar")
        ZipFile(aar.toFile()).use { archive ->
            val classesEntry = assertNotNull(archive.getEntry("classes.jar"))
            JarInputStream(archive.getInputStream(classesEntry)).use { classes ->
                val entries = generateSequence { classes.nextJarEntry }.map { it.name }.toList()
                assertTrue("sample/library/ProtectedLibrary.class" in entries)
                assertTrue(entries.any { it.startsWith("io/github/weg2022/strguard/generated/B") })
                assertFalse(entries.any { it.startsWith("io/github/weg2022/strguard/annotation/") })
            }
            val nativeEntries = archive.entries().asSequence().filter {
                it.name.startsWith("jni/arm64-v8a/libsg_") && it.name.endsWith(".so")
            }.toList()
            assertEquals(if (nativeBuildEnabled) 1 else 0, nativeEntries.size)
            val consumerRules = archive.entries().asSequence()
                .filter { it.name.contains("proguard", ignoreCase = true) || it.name.endsWith(".pro") }
                .any { entry ->
                    archive.getInputStream(entry).bufferedReader().use { reader ->
                        reader.readText().contains("io.github.weg2022.strguard.generated.B*")
                    }
                }
            assertTrue(consumerRules, "AAR does not contain StrGuard's R8 consumer rule")
        }
    }

    private fun verifyApplicationApk(nativeBuildEnabled: Boolean) {
        val apk = findArtifact(projectDirectory.resolve("app/build/outputs/apk"), ".apk")
        ZipFile(apk.toFile()).use { archive ->
            val dexEntries = archive.entries().asSequence().filter {
                it.name.startsWith("classes") && it.name.endsWith(".dex")
            }.toList()
            assertTrue(dexEntries.isNotEmpty())
            dexEntries.forEach { entry ->
                val contents = archive.getInputStream(entry).readBytes().toString(StandardCharsets.ISO_8859_1)
                assertFalse(contents.contains("android-app-sensitive-value"))
                assertFalse(contents.contains("android-library-sensitive-value"))
            }
            val nativeEntries = archive.entries().asSequence().filter {
                it.name.startsWith("lib/arm64-v8a/libsg_") && it.name.endsWith(".so")
            }.toList()
            assertEquals(if (nativeBuildEnabled) 2 else 0, nativeEntries.size)
        }
    }

    private fun verifyR8BridgeNames() {
        val apk = findArtifact(projectDirectory.resolve("app/build/outputs/apk"), ".apk")
        val dexText = ZipFile(apk.toFile()).use { archive ->
            archive.entries().asSequence()
                .filter { it.name.startsWith("classes") && it.name.endsWith(".dex") }
                .joinToString(separator = "") { entry ->
                    archive.getInputStream(entry).readBytes().toString(StandardCharsets.ISO_8859_1)
                }
        }
        listOf("app", "library").forEach { module ->
            val config = Files.readString(
                projectDirectory.resolve("$module/build/strguard/native-input/release/native_config.rs"),
            )
            val generatedNames = Regex("\"([^\"]+)\"").findAll(config).map { it.groupValues[1] }.take(5).toList()
            assertEquals(5, generatedNames.size)
            val bridgeClass = generatedNames.first()
            assertTrue(dexText.contains(bridgeClass), "R8 renamed or removed generated bridge $bridgeClass")
            generatedNames.drop(1).forEach { methodName ->
                assertTrue(dexText.contains(methodName), "R8 renamed or removed Native method $methodName")
            }
        }
    }

    private fun findArtifact(directory: Path, extension: String): Path =
        Files.walk(directory).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(extension) }
                .findFirst()
                .orElseThrow { AssertionError("No $extension artifact found under $directory") }
        }

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDirectory.toFile())
            .withArguments(*arguments, "--stacktrace")
            .forwardOutput()

    private fun writeFile(relativePath: String, contents: String) {
        val file = projectDirectory.resolve(relativePath)
        Files.createDirectories(file.parent)
        Files.writeString(file, contents, StandardCharsets.UTF_8)
    }

    private fun projectRootPath(): String =
        Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().toString().replace("\\", "\\\\")
}

private fun findAndroidPackagingSdk(): Path? {
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

private const val ANDROID_MULTI_MODULE_TEST_SEED =
    "123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0"
