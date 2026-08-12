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
                kotlin("multiplatform") version "2.4.10"
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
    fun `Kotlin Multiplatform jvmJar stores and reuses the Gradle configuration cache`() {
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
            rootProject.name = "kmp-config-cache-consumer"
            """.trimIndent(),
        )
        writeFile(
            "build.gradle.kts",
            """
            plugins {
                kotlin("multiplatform") version "2.4.10"
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
            }
            """.trimIndent(),
        )
        writeFile(
            "src/jvmMain/kotlin/sample/Main.kt",
            """
            package sample

            fun reveal(value: String): String = "kmp-cc-prefix-${'$'}value-sensitive-suffix"
            """.trimIndent(),
        )

        val first = runner("jvmJar", "--configuration-cache").build()
        val second = runner("jvmJar", "--configuration-cache").build()

        assertEquals(TaskOutcome.SUCCESS, first.task(":jvmJar")?.outcome)
        // 第二次构建输入未变、输出已存在,jvmJar 复用执行历史为 UP-TO-DATE;
        // 配置缓存复用由下方 "Reusing configuration cache." 断言覆盖。
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":jvmJar")?.outcome)
        assertFalse(
            first.output.contains("Configuration cache entry discarded."),
            "first run must store the configuration cache entry without problems",
        )
        assertTrue(second.output.contains("Reusing configuration cache."))
        // 隐式 metadata target 不再打 pass-through 日志(仅首次构建有配置阶段输出,
        // 配置缓存复用时不会重新配置项目,因此只断言 first)
        assertFalse(first.output.contains("target 'metadata'"))
        // 用户显式声明的非 JVM target 仍保留 pass-through 日志(与既有断言一致)
        assertTrue(
            first.output.contains(
                "StrGuard pass-through: Kotlin Multiplatform target 'js' is not a JVM target",
            ),
        )
        // exclude 语义回归:jar 中恰好只有一个 MainKt.class,且是 transform 后的版本
        val artifact = projectDirectory.resolve("build/libs/kmp-config-cache-consumer-jvm.jar")
        JarFile(artifact.toFile()).use { jar ->
            assertEquals(
                1,
                jar.entries().asSequence().count { it.name == "sample/MainKt.class" },
            )
            val classText =
                jar.getInputStream(jar.getJarEntry("sample/MainKt.class"))
                    .readBytes().toString(StandardCharsets.ISO_8859_1)
            assertFalse(classText.contains("sensitive-suffix"))
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
            "gradle.properties",
            // AGP 9 内置 Kotlin 与 KMP 不兼容（需 com.android.kotlin.multiplatform.library，
            // 且该插件不支持 NDK——StrGuard 的 KMP android 原生集成依赖旧 DSL），
            // 走官方提供的向后兼容路径。jvmargs：TestKit daemon 默认 512m/384m，
            // AGP 9 构建在 CI 实测撑爆 Metaspace
            "org.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=1g\nandroid.builtInKotlin=false\nandroid.newDsl=false\n",
        )
        writeFile(
            "build.gradle.kts",
            """
            plugins {
                kotlin("multiplatform") version "2.4.10"
                id("com.android.library") version "9.3.1"
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
