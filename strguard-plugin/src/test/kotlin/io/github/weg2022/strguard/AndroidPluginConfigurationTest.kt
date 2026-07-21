package io.github.weg2022.strguard

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.objectweb.asm.ClassReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.test.*

class AndroidPluginConfigurationTest {
    @TempDir
    lateinit var projectDirectory: Path

    @ParameterizedTest
    @ValueSource(strings = ["com.android.application", "com.android.library"])
    fun `protects Android application and library classes`(androidPluginId: String) {
        val sdkDirectory = findAndroidSdk()
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
            rootProject.name = "android-${androidPluginId.substringAfterLast('.')}"
            """.trimIndent(),
        )
        writeFile(
            "local.properties",
            "sdk.dir=${availableSdk.toString().replace("\\", "\\\\")}",
        )
        writeFile(
            "build.gradle.kts",
            """
            plugins {
                id("$androidPluginId") version "8.13.2"
                id("io.github.weg2022.strguard")
            }

            repositories {
                google()
                mavenCentral()
            }

            android {
                namespace = "sample.android"
                compileSdk = 34

                defaultConfig {
                    minSdk = 21
                    ${if (androidPluginId == "com.android.application") "applicationId = \"sample.android\"" else ""}
                }

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_11
                    targetCompatibility = JavaVersion.VERSION_11
                }
            }

            strGuard {
                releaseSeedHex.set("$ANDROID_TEST_SEED")
                stringGuardPackages.set(listOf("sample"))
            }
            """.trimIndent(),
        )
        writeFile(
            "src/main/AndroidManifest.xml",
            "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" />",
        )
        writeFile(
            "src/main/java/sample/android/AndroidJavaExample.java",
            """
            package sample.android;

            public final class AndroidJavaExample {
                public static String reveal() {
                    return "android-$androidPluginId-sensitive-value";
                }
            }
            """.trimIndent(),
        )

        val result = runner("transformStrGuardDebugClasses").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":transformStrGuardDebugClasses")?.outcome)
        val transformedJar = findTransformedJar("sample/android/AndroidJavaExample.class")
        JarFile(transformedJar.toFile()).use { jar ->
            val entry = assertNotNull(jar.getJarEntry("sample/android/AndroidJavaExample.class"))
            val classBytes = jar.getInputStream(entry).readBytes()
            assertEquals(ANDROID_JAVA_11_CLASS_VERSION, ClassReader(classBytes).readShort(6).toInt() and 0xffff)
            assertFalse(classBytes.toString(StandardCharsets.ISO_8859_1).contains("sensitive-value"))
            assertTrue(
                jar.entries().asSequence().any {
                    it.name.startsWith("io/github/weg2022/strguard/generated/B") && it.name.endsWith(".class")
                },
            )
        }
        assertTrue(Files.isRegularFile(projectDirectory.resolve("build/strguard/native-input/debug/vault.bin")))
    }

    @Test
    fun `rejects Android minSdk below arm64 runtime floor`() {
        val sdkDirectory = findAndroidSdk()
        assumeTrue(sdkDirectory != null, "Android SDK is not available for AGP integration testing")
        writeContractProject(requireNotNull(sdkDirectory), minSdk = 20)

        val result = runner("help").buildAndFail()

        assertTrue(result.output.contains("requires minSdk >= 21"))
        assertTrue(result.output.contains("variant debug uses minSdk 20"))
    }

    @Test
    fun `rejects ABI filters missing from configured androidAbis`() {
        val sdkDirectory = findAndroidSdk()
        assumeTrue(sdkDirectory != null, "Android SDK is not available for AGP integration testing")
        writeContractProject(
            requireNotNull(sdkDirectory),
            abiFilters = setOf("x86_64"),
            androidAbis = setOf("arm64-v8a"),
        )

        val result = runner("help").buildAndFail()

        assertTrue(result.output.contains("defaultConfig ABI filters x86_64"))
        assertTrue(result.output.contains("missing androidAbis x86_64"))
    }

    @Test
    fun `rejects ABI split outputs missing from configured androidAbis`() {
        val sdkDirectory = findAndroidSdk()
        assumeTrue(sdkDirectory != null, "Android SDK is not available for AGP integration testing")
        writeContractProject(
            requireNotNull(sdkDirectory),
            splitAbis = setOf("x86_64"),
            androidAbis = setOf("arm64-v8a"),
        )

        val result = runner("help").buildAndFail()

        assertTrue(result.output.contains("variant debug ABI split outputs x86_64"))
        assertTrue(result.output.contains("missing androidAbis x86_64"))
    }

    @Test
    fun `protects flavor-specific Android variant`() {
        val sdkDirectory = findAndroidSdk()
        assumeTrue(sdkDirectory != null, "Android SDK is not available for AGP integration testing")
        writeContractProject(requireNotNull(sdkDirectory), withFlavor = true)

        val result = runner("transformStrGuardDemoDebugClasses").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":transformStrGuardDemoDebugClasses")?.outcome)
        assertTrue(Files.isRegularFile(projectDirectory.resolve("build/strguard/native-input/demoDebug/vault.bin")))
        val transformedJar = findTransformedJar("sample/contract/FlavorValue.class")
        JarFile(transformedJar.toFile()).use { jar ->
            val entry = assertNotNull(jar.getJarEntry("sample/contract/FlavorValue.class"))
            val bytes = jar.getInputStream(entry).readBytes().toString(StandardCharsets.ISO_8859_1)
            assertFalse(bytes.contains("flavor-sensitive-value"))
        }
    }

    @Test
    fun `disabled Android plugin does not enforce Native platform contract`() {
        val sdkDirectory = findAndroidSdk()
        assumeTrue(sdkDirectory != null, "Android SDK is not available for AGP integration testing")
        writeContractProject(
            requireNotNull(sdkDirectory),
            minSdk = 20,
            abiFilters = setOf("x86_64"),
            enabled = false,
        )

        val result = runner("assembleDebug", "verifyDisabledStrGuardProviders").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":transformStrGuardDebugClasses")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":buildStrGuardDebugNative")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":assembleDebug")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyDisabledStrGuardProviders")?.outcome)
        val report = Files.readString(projectDirectory.resolve("build/reports/strguard/debug/summary.txt"))
        assertTrue(report.contains("schemaVersion=1"))
        assertTrue(report.contains("enabled=false"))
        assertTrue(report.contains("runtimeTarget=disabled"))
        assertFalse(report.contains("disabled Android seed Provider was evaluated"))
        assertFalse(result.output.contains("disabled Android ABI Provider was evaluated"))
    }

    private fun writeContractProject(
        sdkDirectory: Path,
        minSdk: Int = 21,
        abiFilters: Set<String> = emptySet(),
        splitAbis: Set<String> = emptySet(),
        androidAbis: Set<String> = AndroidAbi.entries.map(AndroidAbi::abiName).toSet(),
        enabled: Boolean = true,
        withFlavor: Boolean = false,
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
            rootProject.name = "android-contract"
            """.trimIndent(),
        )
        writeFile("local.properties", "sdk.dir=${sdkDirectory.toString().replace("\\", "\\\\")}")
        writeFile(
            "src/main/AndroidManifest.xml",
            "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" />",
        )
        writeFile(
            "src/main/java/sample/contract/FlavorValue.java",
            """
            package sample.contract;

            public final class FlavorValue {
                public static String reveal() {
                    return "flavor-sensitive-value";
                }
            }
            """.trimIndent(),
        )
        val abiConfiguration = abiFilters.joinToString { "\"$it\"" }
        val splitAbiConfiguration = splitAbis.joinToString { "\"$it\"" }
        val strGuardAbiConfiguration = androidAbis.joinToString { "\"$it\"" }
        val strGuardAbis =
            if (enabled) {
                "setOf($strGuardAbiConfiguration)"
            } else {
                """
                providers.provider<Set<String>> {
                    error("disabled Android ABI Provider was evaluated")
                }
                """.trimIndent()
            }
        writeFile(
            "build.gradle.kts",
            """
            import io.github.weg2022.strguard.BuildAndroidNativeRuntimeTask
            import io.github.weg2022.strguard.TransformAndroidClassesTask

            plugins {
                id("com.android.application") version "8.13.2"
                id("io.github.weg2022.strguard")
            }

            android {
                namespace = "sample.contract"
                compileSdk = 34
                defaultConfig {
                    applicationId = "sample.contract"
                    minSdk = $minSdk
                    ${if (abiFilters.isEmpty()) "" else "ndk.abiFilters += setOf($abiConfiguration)"}
                }
                ${
                if (withFlavor) {
                    """
                    flavorDimensions += "tier"
                    productFlavors {
                        create("demo") {
                            dimension = "tier"
                        }
                    }
                    """.trimIndent()
                } else {
                    ""
                }
            }
                ${
                if (splitAbis.isEmpty()) {
                    ""
                } else {
                    """
                    splits {
                        abi {
                            isEnable = true
                            reset()
                            include($splitAbiConfiguration)
                        }
                    }
                    """.trimIndent()
                }
            }
            }

            strGuard {
                enabled.set($enabled)
                androidAbis.set($strGuardAbis)
                ${
                if (enabled) {
                    """
                    releaseSeedHex.set("$ANDROID_TEST_SEED")
                    stringGuardPackages.set(listOf("sample"))
                    """.trimIndent()
                } else {
                    """
                    releaseSeedHex.set(providers.provider<String> {
                        error("disabled Android seed Provider was evaluated")
                    })
                    targetTriple.set(providers.provider<String> {
                        error("disabled Android target Provider was evaluated")
                    })
                    """.trimIndent()
                }
            }
            }

            ${
                if (enabled) {
                    ""
                } else {
                    """
                    tasks.register("verifyDisabledStrGuardProviders") {
                        doLast {
                            project.tasks.named<TransformAndroidClassesTask>("transformStrGuardDebugClasses").get()
                                .let { task ->
                                    check(task.releaseSeedHex.get() == "disabled")
                                    check(task.releaseSeedFingerprint.get() == "disabled")
                                    check(task.group == "strguard")
                                    check(!task.description.isNullOrBlank())
                                }
                            project.tasks.named<BuildAndroidNativeRuntimeTask>("buildStrGuardDebugArm64V8aNative").get()
                                .let { task ->
                                    check(task.abiName.get() == "arm64-v8a")
                                    check(task.ndkHostTag.get() == "disabled")
                                    check(task.ndkVersion.get() == "disabled")
                                    check(task.cargoExecutable.get() == "disabled")
                                    check(task.ndkDirectory.get().asFile.name == "disabled-ndk")
                                    check(task.group == "strguard")
                                    check(!task.description.isNullOrBlank())
                                }
                            project.tasks.getByName("buildStrGuardDebugNative").let { task ->
                                check(task.group == "strguard")
                                check(!task.description.isNullOrBlank())
                            }
                        }
                    }
                    """.trimIndent()
                }
            }
            """.trimIndent(),
        )
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

    private fun projectRootPath(): String = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().toString().replace("\\", "\\\\")
}

private fun findAndroidSdk(): Path? {
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

private const val ANDROID_TEST_SEED =
    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
private const val ANDROID_JAVA_11_CLASS_VERSION = 55
