package io.github.weg2022.strguard

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.jar.JarInputStream
import java.util.zip.ZipFile
import kotlin.test.*

class KotlinMultiplatformPluginFunctionalTest {
    @TempDir
    lateinit var projectDirectory: Path

    @Test
    fun `protects Kotlin Multiplatform JVM target and leaves JS target unchanged`() {
        val nativeTarget = hostNativeTarget()
        writeFile(
            "settings.gradle.kts",
            """
            pluginManagement {
                includeBuild("${projectRootPath()}")
                repositories {
                    gradlePluginPortal()
                    mavenCentral()
                }
            }
            rootProject.name = "kmp-consumer"
            """.trimIndent(),
        )
        writeFile(
            "build.gradle.kts",
            """
            plugins {
                kotlin("multiplatform") version "2.1.21"
                id("io.github.weg2022.strguard")
            }

            repositories {
                mavenCentral()
            }

            kotlin {
                jvm()
                js(IR) {
                    nodejs()
                }
            }

            strGuard {
                releaseSeedHex.set("$KMP_TEST_SEED")
                targetTriple.set("${nativeTarget.rustTriple}")
                stringGuardPackages.set(listOf("sample"))
                consoleOutput.set(true)
            }

            tasks.register("verifyStrGuardKmpTasks") {
                doLast {
                    check("transformStrGuardJvmMain" in project.tasks.names)
                    check("buildStrGuardJvmNative" in project.tasks.names)
                    check("transformStrGuardJsMain" !in project.tasks.names)
                    check("buildStrGuardJsNative" !in project.tasks.names)
                    listOf("transformStrGuardJvmMain", "buildStrGuardJvmNative").forEach { taskName ->
                        val publicTask = project.tasks.getByName(taskName)
                        check(publicTask.group == "strguard")
                        check(!publicTask.description.isNullOrBlank())
                    }
                }
            }
            """.trimIndent(),
        )
        writeFile(
            "src/jsMain/kotlin/sample/JsMessage.kt",
            """
            package sample

            fun jsReveal(): String = "kmp-js-unchanged-sensitive-value"
            """.trimIndent(),
        )
        writeFile(
            "src/jvmMain/kotlin/sample/Main.kt",
            """
            package sample

            fun reveal(value: String): String = "kmp-prefix-${'$'}value-sensitive-suffix"

            fun main() {
                println(reveal("runtime"))
            }
            """.trimIndent(),
        )

        val result =
            runner(
                "jvmJar",
                "jvmRun",
                "jsMainClasses",
                "verifyStrGuardKmpTasks",
                "-DmainClass=sample.MainKt",
            ).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":transformStrGuardJvmMain")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":jsMainClasses")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyStrGuardKmpTasks")?.outcome)
        assertEquals(null, result.task(":transformStrGuardJsMain"))
        assertEquals(null, result.task(":buildStrGuardJsNative"))
        assertTrue(
            result.output.contains(
                "StrGuard pass-through: Kotlin Multiplatform target 'js' is not a JVM target",
            ),
        )
        assertTrue(result.output.contains("kmp-prefix-runtime-sensitive-suffix"))
        val transformedClass = projectDirectory.resolve("build/strguard/classes/jvm/main/sample/MainKt.class")
        assertFalse(classContains(transformedClass, "sensitive-suffix"))
        val artifact = projectDirectory.resolve("build/libs/kmp-consumer-jvm.jar")
        JarFile(artifact.toFile()).use { jar ->
            val entry = assertNotNull(jar.getJarEntry("sample/MainKt.class"))
            val classText = jar.getInputStream(entry).readBytes().toString(StandardCharsets.ISO_8859_1)
            assertFalse(classText.contains("sensitive-suffix"))
            assertTrue(
                jar.entries().asSequence().any {
                    it.name.startsWith("META-INF/strguard/native/${nativeTarget.packagingDirectory}/") &&
                        it.name.endsWith(nativeTarget.libraryExtension)
                },
            )
        }
    }

    @Test
    fun `protects Kotlin Multiplatform Android target through AGP variants`() {
        val sdkDirectory = findKmpAndroidSdk()
        assumeTrue(sdkDirectory != null, "Android SDK is not available for KMP Android testing")
        val nativeBuildEnabled =
            System.getenv("STRGUARD_ANDROID_NATIVE_TEST").equals("true", ignoreCase = true)
        val ndkVersion = System.getenv("ANDROID_NDK_VERSION")
        if (nativeBuildEnabled) {
            assertFalse(ndkVersion.isNullOrBlank(), "ANDROID_NDK_VERSION is required for Native integration testing")
        }
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
            dependencyResolutionManagement {
                repositories {
                    google()
                    mavenCentral()
                }
            }
            rootProject.name = "kmp-android-consumer"
            """.trimIndent(),
        )
        writeFile("local.properties", "sdk.dir=${availableSdk.toString().replace("\\", "\\\\")}")
        writeFile(
            "build.gradle.kts",
            """
            plugins {
                kotlin("multiplatform") version "2.1.21"
                id("com.android.library") version "8.13.2"
                id("io.github.weg2022.strguard")
            }

            kotlin {
                androidTarget()
            }

            android {
                namespace = "sample.kmp.android"
                compileSdk = 34
                ${ndkVersion?.let { "ndkVersion = \"$it\"" }.orEmpty()}
                defaultConfig {
                    minSdk = 21
                }
            }

            strGuard {
                releaseSeedHex.set("$KMP_TEST_SEED")
                stringGuardPackages.set(listOf("sample"))
            }

            tasks.register("verifyKmpAndroidTasks") {
                doLast {
                    check("transformStrGuardDebugClasses" in project.tasks.names)
                    check("buildStrGuardDebugNative" in project.tasks.names)
                    check("transformStrGuardAndroidMain" !in project.tasks.names)
                    check("buildStrGuardAndroidNative" !in project.tasks.names)
                }
            }
            """.trimIndent(),
        )
        writeFile(
            "src/main/AndroidManifest.xml",
            "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" />",
        )
        writeFile(
            "src/androidMain/kotlin/sample/kmp/android/KmpAndroidValue.kt",
            """
            package sample.kmp.android

            fun revealKmpAndroid(): String = "kmp-android-sensitive-value"
            """.trimIndent(),
        )

        val result =
            if (nativeBuildEnabled) {
                runner("assembleDebug", "verifyKmpAndroidTasks").build()
            } else {
                runner("transformStrGuardDebugClasses", "verifyKmpAndroidTasks").build()
            }

        assertEquals(TaskOutcome.SUCCESS, result.task(":transformStrGuardDebugClasses")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyKmpAndroidTasks")?.outcome)
        assertTrue(
            result.output.contains(
                "StrGuard pass-through: Kotlin Multiplatform target 'android' is not a JVM target",
            ),
        )
        val transformedJar = findJarContaining("sample/kmp/android/KmpAndroidValueKt.class")
        JarFile(transformedJar.toFile()).use { jar ->
            val entry = assertNotNull(jar.getJarEntry("sample/kmp/android/KmpAndroidValueKt.class"))
            val bytes = jar.getInputStream(entry).readBytes()
            assertFalse(bytes.toString(StandardCharsets.ISO_8859_1).contains("kmp-android-sensitive-value"))
        }
        if (nativeBuildEnabled) {
            AndroidAbi.entries.forEach { abi ->
                assertEquals(
                    TaskOutcome.SUCCESS,
                    result.task(":buildStrGuardDebug${abi.taskSuffix}Native")?.outcome,
                )
            }
            val aar = Files.walk(projectDirectory.resolve("build/outputs/aar")).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".aar") }
                    .findFirst()
                    .orElseThrow { AssertionError("KMP Android build did not produce an AAR") }
            }
            ZipFile(aar.toFile()).use { archive ->
                val nativeAbis =
                    archive.entries().asSequence()
                        .filter { entry -> entry.name.matches(Regex("jni/[^/]+/libsg_.+[.]so")) }
                        .map { entry -> entry.name.substringAfter("jni/").substringBefore('/') }
                        .toSet()
                assertEquals(AndroidAbi.entries.map(AndroidAbi::abiName).toSet(), nativeAbis)
                val classes = assertNotNull(archive.getEntry("classes.jar"))
                JarInputStream(archive.getInputStream(classes)).use { jar ->
                    val entries = generateSequence { jar.nextJarEntry }.map { entry -> entry.name }.toList()
                    assertTrue("sample/kmp/android/KmpAndroidValueKt.class" in entries)
                    assertTrue(entries.any { entry -> entry.startsWith("META-INF/strguard/artifacts/") })
                }
            }
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

    private fun classContains(classFile: Path, value: String): Boolean = Files.readAllBytes(classFile).toString(StandardCharsets.ISO_8859_1).contains(value)

    private fun findJarContaining(requiredEntry: String): Path = Files.walk(projectDirectory.resolve("build")).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
            .filter { candidate ->
                runCatching { JarFile(candidate.toFile()).use { jar -> jar.getJarEntry(requiredEntry) != null } }
                    .getOrDefault(false)
            }
            .findFirst()
            .orElseThrow { AssertionError("No transformed JAR contains $requiredEntry") }
    }

    private fun projectRootPath(): String = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().toString().replace("\\", "\\\\")

    private fun hostNativeTarget(): JvmNativeTarget = JvmNativeTarget.detectHost(System.getProperty("os.name"), System.getProperty("os.arch"))

    private fun findKmpAndroidSdk(): Path? = sequenceOf(
        System.getProperty("android.sdk.path"),
        System.getenv("ANDROID_HOME"),
        System.getenv("ANDROID_SDK_ROOT"),
    ).filterNotNull().map(Path::of).plus(
        sequenceOf(
            Path.of(System.getProperty("user.home"), "AppData", "Local", "Android", "Sdk"),
            Path.of(System.getProperty("user.home"), "Android", "Sdk"),
        ),
    ).firstOrNull { sdk -> Files.isRegularFile(sdk.resolve("platforms/android-34/android.jar")) }
}

private const val KMP_TEST_SEED =
    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
