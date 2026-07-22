package io.github.weg2022.strguard

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopShrinkerFunctionalTest {
    @TempDir(cleanup = CleanupMode.NEVER)
    lateinit var projectDirectory: Path

    @AfterEach
    fun cleanTestProject() {
        if (projectDirectory.toFile().deleteRecursively() || !Files.exists(projectDirectory)) return
        // TestKit daemons can retain consumer JAR handles on Windows until the test JVM exits.
        Files.walk(projectDirectory).use { paths -> paths.forEach { path -> path.toFile().deleteOnExit() } }
    }

    @Test
    fun `public artifact contract verifies and marks a shrinker output`() {
        writeFile("settings.gradle.kts", "rootProject.name = \"shrinker-consumer\"")
        writeFile(
            "build.gradle.kts",
            """
            plugins {
                java
                `maven-publish`
                id("io.github.weg2022.strguard")
            }

            group = "sample"
            version = "1.0"

            strGuard {
                releaseSeedHex.set("$SHRINKER_TEST_SEED")
            }

            val artifact = strGuardArtifacts.jvm("main")
            val rawShrunkJar = layout.buildDirectory.file("shrinker/raw.jar")
            val fakeShrink = tasks.register("fakeShrink") {
                dependsOn(artifact.protectedJar)
                inputs.file(artifact.protectedJar)
                outputs.file(rawShrunkJar)
                doLast {
                    val output = rawShrunkJar.get().asFile
                    output.parentFile.mkdirs()
                    artifact.protectedJar.get().asFile.copyTo(output, overwrite = true)
                }
            }
            val verifiedJar = artifact.verifyShrunkJar(
                fakeShrink.map { rawShrunkJar.get() },
                "copy-fixture:1",
            )
            tasks.register("assertVerifiedShrunkJar") {
                dependsOn(verifiedJar)
                doLast {
                    check(verifiedJar.get().asFile.isFile)
                    check(artifact.requiredShrinkerRules.get().asFile.readText().contains("generated.L*"))
                }
            }
            publishing {
                publications {
                    create<org.gradle.api.publish.maven.MavenPublication>("protected") {
                        artifact(verifiedJar)
                    }
                }
                repositories {
                    maven {
                        name = "test"
                        url = uri(layout.buildDirectory.dir("repository"))
                    }
                }
            }
            """.trimIndent(),
        )
        writeFile(
            "src/main/java/sample/ShrunkExample.java",
            """
            package sample;

            public final class ShrunkExample {
                public static String reveal() {
                    return "desktop-shrinker-sensitive-value";
                }
            }
            """.trimIndent(),
        )

        val result = runner("assertVerifiedShrunkJar", "publishProtectedPublicationToTestRepository").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":fakeShrink")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyStrGuardMainShrunkArtifact")?.outcome)
        val verifiedJar = projectDirectory.resolve("build/strguard/shrinker/main/verified.jar")
        val publishedJar =
            projectDirectory.resolve("build/repository/sample/shrinker-consumer/1.0/shrinker-consumer-1.0.jar")
        assertEquals(TaskOutcome.SUCCESS, result.task(":publishProtectedPublicationToTestRepository")?.outcome)
        assertContentEquals(Files.readAllBytes(verifiedJar), Files.readAllBytes(publishedJar))
        JarFile(verifiedJar.toFile()).use { jar ->
            val markerEntry =
                assertNotNull(
                    jar.entries().asSequence().singleOrNull {
                        it.name.startsWith("META-INF/strguard/artifacts/") && it.name.endsWith(".properties")
                    },
                )
            val marker = Properties().apply { jar.getInputStream(markerEntry).use(::load) }
            assertEquals("shrunk", marker.getProperty("stage"))
            assertEquals("copy-fixture:1", marker.getProperty("shrinkerId"))
            assertEquals(8, marker.getProperty("gatewayNames").split(',').size)
            assertTrue(
                jar.entries().asSequence().any {
                    it.name.startsWith("META-INF/strguard/native/") && it.name.endsWith(hostNativeTarget().libraryExtension)
                },
            )
        }
        JarFile(publishedJar.toFile()).use { jar ->
            val marker =
                jar.entries().asSequence().single { entry ->
                    entry.name.startsWith("META-INF/strguard/artifacts/") && entry.name.endsWith(".properties")
                }
            val metadata = Properties().apply { jar.getInputStream(marker).use(::load) }
            assertEquals("shrunk", metadata.getProperty("stage"))
        }
        URLClassLoader(arrayOf(verifiedJar.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val example = Class.forName("sample.ShrunkExample", true, loader)
            assertEquals("desktop-shrinker-sensitive-value", example.getMethod("reveal").invoke(null))
        }
        val protectedJar = projectDirectory.resolve("build/libs/shrinker-consumer-1.0.jar")
        val failure =
            assertFailsWith<org.gradle.api.GradleException> {
                StrGuardShrunkArtifactFinalizer.finalize(
                    protectedJar = protectedJar,
                    shrunkJar = verifiedJar,
                    verifiedJar = projectDirectory.resolve("build/shrinker/double-stage.jar"),
                    shrinkerId = "second-pass",
                )
            }
        assertTrue(failure.message.orEmpty().contains("already a finalized StrGuard artifact"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["java", "application", "`java-library`"])
    fun `ProGuard supports standard JVM plugin artifacts`(pluginDeclaration: String) {
        writeFile("settings.gradle.kts", "rootProject.name = \"proguard-consumer\"")
        writeFile(
            "build.gradle.kts",
            """
            import proguard.gradle.ProGuardTask

            buildscript {
                repositories { mavenCentral() }
                dependencies { classpath("com.guardsquare:proguard-gradle:7.7.0") }
            }

            plugins {
                $pluginDeclaration
                id("io.github.weg2022.strguard")
            }

            strGuard {
                releaseSeedHex.set("$SHRINKER_TEST_SEED")
            }

            val artifact = strGuardArtifacts.jvm("main")
            val rawShrunkJar = layout.buildDirectory.file("shrinker/proguard-raw.jar")
            val shrink = tasks.register<ProGuardTask>("proguardMain") {
                dependsOn(artifact.protectedJar)
                injars(artifact.protectedJar.get().asFile)
                outjars(rawShrunkJar.get().asFile)
                configuration(artifact.requiredShrinkerRules.get().asFile)
                libraryjars(
                    mapOf("jarfilter" to "!**.jar", "filter" to "!module-info.class"),
                    file("${'$'}{System.getProperty("java.home")}/jmods/java.base.jmod"),
                )
                keep("public class sample.ProtectedApp { public static java.lang.String reveal(); }")
                dontwarn()
                dontnote()
                allowaccessmodification()
                repackageclasses("obfuscated")
            }
            val verifiedJar = artifact.verifyShrunkJar(
                shrink.map { rawShrunkJar.get() },
                "proguard:7.7.0",
            )
            tasks.register("assertVerifiedProGuardJar") {
                dependsOn(verifiedJar)
                doLast { check(verifiedJar.get().asFile.isFile) }
            }
            """.trimIndent(),
        )
        writeFile(
            "src/main/java/sample/ProtectedApp.java",
            """
            package sample;

            public final class ProtectedApp {
                public static String reveal() {
                    return "proguard-sensitive-value";
                }
            }
            """.trimIndent(),
        )

        val result = runner("assertVerifiedProGuardJar").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":proguardMain")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyStrGuardMainShrunkArtifact")?.outcome)
        val verifiedJar = projectDirectory.resolve("build/strguard/shrinker/main/verified.jar")
        JarFile(verifiedJar.toFile()).use { jar ->
            val markerEntry =
                assertNotNull(
                    jar.entries().asSequence().singleOrNull {
                        it.name.startsWith("META-INF/strguard/artifacts/") && it.name.endsWith(".properties")
                    },
                )
            val marker = Properties().apply { jar.getInputStream(markerEntry).use(::load) }
            assertEquals("shrunk", marker.getProperty("stage"))
            assertEquals("proguard:7.7.0", marker.getProperty("shrinkerId"))
            assertNotNull(jar.getJarEntry("${marker.getProperty("bridgeClass")}.class"))
            assertNotNull(jar.getJarEntry("${marker.getProperty("loaderClass")}.class"))
        }
        URLClassLoader(arrayOf(verifiedJar.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val example = Class.forName("sample.ProtectedApp", true, loader)
            assertEquals("proguard-sensitive-value", example.getMethod("reveal").invoke(null))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["7.7.0", "7.9.1"])
    fun `ProGuard supports Kotlin Multiplatform desktop JVM artifact`(proguardVersion: String) {
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
            rootProject.name = "kmp-proguard-consumer"
            """.trimIndent(),
        )
        writeFile(
            "build.gradle.kts",
            """
            plugins {
                kotlin("multiplatform") version "2.1.21"
                id("io.github.weg2022.strguard")
            }

            repositories { mavenCentral() }

             val proguardTool by configurations.creating
             dependencies {
                add(proguardTool.name, "com.guardsquare:proguard-base:$proguardVersion")
            }

            kotlin {
                jvm("desktop")
            }

            strGuard {
                releaseSeedHex.set("$SHRINKER_TEST_SEED")
            }

            val artifact = strGuardArtifacts.jvm("desktop")
            val rawShrunkJar = layout.buildDirectory.file("shrinker/proguard-raw.jar")
            val shrink = tasks.register<JavaExec>("proguardDesktop") {
                dependsOn(artifact.protectedJar)
                inputs.files(artifact.protectedJar, artifact.requiredShrinkerRules)
                outputs.file(rawShrunkJar)
                classpath = proguardTool
                mainClass.set("proguard.ProGuard")
                val javaBase = file("${'$'}{System.getProperty("java.home")}/jmods/java.base.jmod")
                args(
                    "-injars", artifact.protectedJar.get().asFile.absolutePath,
                    "-outjars", rawShrunkJar.get().asFile.absolutePath,
                    "-libraryjars", "${'$'}{javaBase.absolutePath}(!**.jar;!module-info.class)",
                    "-include", artifact.requiredShrinkerRules.get().asFile.absolutePath,
                    "-keep", "public class sample.DesktopProtected { public static java.lang.String reveal(); }",
                    "-dontwarn",
                    "-dontnote",
                    "-allowaccessmodification",
                    "-repackageclasses", "obfuscated",
                )
            }
             val verifiedJar = artifact.verifyShrunkJar(
                 shrink.map { rawShrunkJar.get() },
                "proguard:$proguardVersion",
            )
            tasks.register("assertVerifiedDesktopJar") {
                dependsOn(verifiedJar)
                doLast { check(verifiedJar.get().asFile.isFile) }
            }
            """.trimIndent(),
        )
        writeFile(
            "src/desktopMain/kotlin/sample/DesktopProtected.kt",
            """
            package sample

            object DesktopProtected {
                @JvmStatic
                fun reveal(): String = "kmp-desktop-proguard-sensitive-value"
            }
            """.trimIndent(),
        )

        val result = includedBuildRunner("assertVerifiedDesktopJar").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":proguardDesktop")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyStrGuardDesktopShrunkArtifact")?.outcome)
        val verifiedJar = projectDirectory.resolve("build/strguard/shrinker/desktop/verified.jar")
        URLClassLoader(arrayOf(verifiedJar.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val example = Class.forName("sample.DesktopProtected", true, loader)
            assertEquals(
                "kmp-desktop-proguard-sensitive-value",
                example.getMethod("reveal").invoke(null),
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["8.13.19", "9.1.31"])
    fun `standalone R8 classfile mode verifies and runs protected JVM artifact`(r8Version: String) {
        writeFile("settings.gradle.kts", "rootProject.name = \"r8-consumer\"")
        writeFile(
            "build.gradle.kts",
            """
            plugins {
                java
                id("io.github.weg2022.strguard")
            }

            repositories {
                google()
                mavenCentral()
            }

            val r8Tool by configurations.creating
            dependencies {
                add(r8Tool.name, "com.android.tools:r8:$r8Version")
            }

            strGuard {
                releaseSeedHex.set("$SHRINKER_TEST_SEED")
            }

            val artifact = strGuardArtifacts.jvm("main")
            val rawShrunkJar = layout.buildDirectory.file("shrinker/r8-raw.jar")
            val shrink = tasks.register<JavaExec>("r8Main") {
                dependsOn(artifact.protectedJar)
                inputs.files(artifact.protectedJar, artifact.requiredShrinkerRules, "r8-rules.pro")
                outputs.file(rawShrunkJar)
                classpath = r8Tool
                mainClass.set("com.android.tools.r8.R8")
                doFirst { rawShrunkJar.get().asFile.parentFile.mkdirs() }
                args(
                    "--release",
                    "--classfile",
                    "--output", rawShrunkJar.get().asFile.absolutePath,
                    "--pg-conf", artifact.requiredShrinkerRules.get().asFile.absolutePath,
                    "--pg-conf", file("r8-rules.pro").absolutePath,
                    "--lib", System.getProperty("java.home"),
                    artifact.protectedJar.get().asFile.absolutePath,
                )
            }
            val verifiedJar = artifact.verifyShrunkJar(
                shrink.map { rawShrunkJar.get() },
                "r8:$r8Version",
            )
            tasks.register("assertVerifiedR8Jar") {
                dependsOn(verifiedJar)
                doLast { check(verifiedJar.get().asFile.isFile) }
            }
            """.trimIndent(),
        )
        writeFile(
            "r8-rules.pro",
            """
            -keep public class sample.R8Protected { public static java.lang.String reveal(); }
            -dontwarn **
            -dontnote **
            -allowaccessmodification
            -repackageclasses obfuscated
            """.trimIndent(),
        )
        writeFile(
            "src/main/java/sample/R8Protected.java",
            """
            package sample;

            public final class R8Protected {
                public static String reveal() {
                    return "r8-classfile-sensitive-value";
                }
            }
            """.trimIndent(),
        )

        val result = runner("assertVerifiedR8Jar").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":r8Main")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyStrGuardMainShrunkArtifact")?.outcome)
        val verifiedJar = projectDirectory.resolve("build/strguard/shrinker/main/verified.jar")
        JarFile(verifiedJar.toFile()).use { jar ->
            val markerEntry =
                assertNotNull(
                    jar.entries().asSequence().singleOrNull { entry ->
                        entry.name.startsWith("META-INF/strguard/artifacts/") && entry.name.endsWith(".properties")
                    },
                )
            val marker = Properties().apply { jar.getInputStream(markerEntry).use(::load) }
            assertEquals("shrunk", marker.getProperty("stage"))
            assertEquals("r8:$r8Version", marker.getProperty("shrinkerId"))
        }
        URLClassLoader(arrayOf(verifiedJar.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val example = Class.forName("sample.R8Protected", true, loader)
            assertEquals("r8-classfile-sensitive-value", example.getMethod("reveal").invoke(null))
        }
    }

    @Test
    fun `whole program ProGuard supports three protected modules`() {
        writeFile(
            "settings.gradle",
            """
            pluginManagement {
                repositories {
                    gradlePluginPortal()
                    mavenCentral()
                }
            }
            rootProject.name = 'whole-program'
            include 'one', 'two', 'three'
            """.trimIndent(),
        )
        writeFile(
            "build.gradle",
            """
            plugins {
                id 'io.github.weg2022.strguard' apply false
            }

            repositories { mavenCentral() }
            configurations { proguardTool }
            dependencies { proguardTool 'com.guardsquare:proguard-base:7.9.1' }

            subprojects {
                apply plugin: 'java-library'
                apply plugin: 'io.github.weg2022.strguard'
                strGuard {
                    releaseSeedHex.set('$SHRINKER_TEST_SEED')
                    stringGuardPackages.set(['sample'])
                }
            }

            evaluationDependsOnChildren()
            def modules = ['one', 'two', 'three'].collect { project(":${'$'}{it}") }
            def artifacts = modules.collect { it.extensions.strGuardArtifacts.jvm('main') }
            def rawShrunkJar = layout.buildDirectory.file('shrinker/proguard-all-raw.jar')
            def shrink = tasks.register('proguardAll', JavaExec) {
                dependsOn artifacts.collect { it.protectedJar }
                inputs.files artifacts.collect { it.protectedJar }
                inputs.files artifacts.collect { it.requiredShrinkerRules }
                outputs.file rawShrunkJar
                classpath = configurations.proguardTool
                mainClass.set('proguard.ProGuard')
                doFirst {
                    rawShrunkJar.get().asFile.parentFile.mkdirs()
                    def command = []
                    artifacts.each { artifact ->
                        command += ['-injars', artifact.protectedJar.get().asFile.absolutePath]
                    }
                    command += [
                        '-outjars', rawShrunkJar.get().asFile.absolutePath,
                        '-libraryjars', System.getProperty('java.home') + '/jmods/java.base.jmod(!**.jar;!module-info.class)',
                        '-include', artifacts.first().requiredShrinkerRules.get().asFile.absolutePath,
                        '-keep', 'public class sample.one.OneValue { public static java.lang.String reveal(); }',
                        '-keep', 'public class sample.two.TwoValue { public static java.lang.String reveal(); }',
                        '-keep', 'public class sample.three.ThreeValue { public static java.lang.String reveal(); }',
                        '-dontwarn',
                        '-dontnote',
                        '-allowaccessmodification',
                        '-repackageclasses', 'obfuscated',
                    ]
                    setArgs(command)
                }
            }
            def verified = shrink.map { rawShrunkJar.get() }
            artifacts.eachWithIndex { artifact, index ->
                verified = artifact.verifyShrunkJar(verified, "proguard:7.9.1:module-${'$'}{index + 1}")
            }
            tasks.register('assertWholeProgramJar') {
                dependsOn verified
                doLast { assert verified.get().asFile.isFile() }
            }
            """.trimIndent(),
        )
        listOf("one", "two", "three").forEach { module ->
            val className = module.replaceFirstChar(Char::uppercaseChar) + "Value"
            writeFile(
                "$module/src/main/java/sample/$module/$className.java",
                """
                package sample.$module;

                public final class $className {
                    public static String reveal() {
                        return "$module-whole-program-sensitive-value";
                    }
                }
                """.trimIndent(),
            )
        }

        val result = runner("assertWholeProgramJar").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":proguardAll")?.outcome)
        listOf("one", "two", "three").forEach { module ->
            assertEquals(TaskOutcome.SUCCESS, result.task(":$module:verifyStrGuardMainShrunkArtifact")?.outcome)
        }
        val verifiedJar = projectDirectory.resolve("three/build/strguard/shrinker/main/verified.jar")
        JarFile(verifiedJar.toFile()).use { jar ->
            val markers =
                jar.entries().asSequence()
                    .filter { entry ->
                        entry.name.startsWith("META-INF/strguard/artifacts/") && entry.name.endsWith(".properties")
                    }.map { entry -> Properties().apply { jar.getInputStream(entry).use(::load) } }
                    .toList()
            assertEquals(3, markers.size)
            assertTrue(markers.all { marker -> marker.getProperty("stage") == "shrunk" })
            assertEquals(3, markers.map { marker -> marker.getProperty("loaderClass") }.toSet().size)
            assertEquals(
                3,
                jar.entries().asSequence().count { entry ->
                    entry.name.startsWith("META-INF/strguard/native/") &&
                        entry.name.endsWith(hostNativeTarget().libraryExtension)
                },
            )
        }
        URLClassLoader(arrayOf(verifiedJar.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            listOf("one", "two", "three").forEach { module ->
                val className = module.replaceFirstChar(Char::uppercaseChar) + "Value"
                val type = Class.forName("sample.$module.$className", true, loader)
                assertEquals("$module-whole-program-sensitive-value", type.getMethod("reveal").invoke(null))
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `Compose Desktop release ProGuard consumes and verifies protected artifacts`(joinOutputJars: Boolean) {
        writeFile(
            "settings.gradle.kts",
            """
            pluginManagement {
                includeBuild("${projectRootPath()}")
                repositories {
                    gradlePluginPortal()
                    google()
                    mavenCentral()
                }
            }
            rootProject.name = "compose-proguard-consumer"
            include(":library")
            """.trimIndent(),
        )
        writeFile(
            "build.gradle.kts",
            """
            plugins {
                kotlin("multiplatform") version "2.1.21"
                id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
                id("org.jetbrains.compose") version "1.8.2"
                id("io.github.weg2022.strguard")
            }

            repositories {
                google()
                mavenCentral()
            }

            kotlin {
                jvm("desktop")
                sourceSets.named("desktopMain") {
                    dependencies {
                        implementation(compose.desktop.currentOs)
                        implementation(project(":library"))
                    }
                }
            }

            compose.desktop {
                application {
                    mainClass = "sample.MainKt"
                    buildTypes.release.proguard {
                        isEnabled.set(true)
                        obfuscate.set(true)
                        optimize.set(true)
                        joinOutputJars.set($joinOutputJars)
                    }
                }
            }

            strGuard {
                releaseSeedHex.set("$SHRINKER_TEST_SEED")
            }
            """.trimIndent(),
        )
        writeFile(
            "library/build.gradle.kts",
            """
            plugins {
                `java-library`
                id("io.github.weg2022.strguard")
            }

            strGuard {
                releaseSeedHex.set("$SHRINKER_TEST_SEED")
                stringGuardPackages.set(listOf("sample"))
            }
            """.trimIndent(),
        )
        writeFile(
            "library/src/main/java/sample/library/ComposeLibrary.java",
            """
            package sample.library;

            public final class ComposeLibrary {
                public static String reveal() {
                    return "compose-library-sensitive-value";
                }
            }
            """.trimIndent(),
        )
        writeFile(
            "src/desktopMain/kotlin/sample/Main.kt",
            """
            package sample

            import sample.library.ComposeLibrary

            fun main() {
                println(reveal())
            }

            fun reveal(): String = "compose-desktop-proguard-sensitive-value|${'$'}{ComposeLibrary.reveal()}"
            """.trimIndent(),
        )

        val distributionTestEnabled =
            System.getenv("STRGUARD_COMPOSE_DISTRIBUTION_TEST").equals("true", ignoreCase = true)
        val tasks =
            if (distributionTestEnabled) {
                arrayOf("runRelease", "createReleaseDistributable")
            } else {
                arrayOf("runRelease")
            }
        val result = includedBuildRunner(*tasks).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":proguardReleaseJars")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":runRelease")?.outcome)
        if (distributionTestEnabled) {
            assertEquals(TaskOutcome.SUCCESS, result.task(":createReleaseDistributable")?.outcome)
        }
        assertTrue(
            result.output.contains(
                "compose-desktop-proguard-sensitive-value|compose-library-sensitive-value",
            ),
        )
        val markers =
            Files.walk(projectDirectory.resolve("build/compose/tmp/main-release/proguard")).use { paths ->
                paths.iterator().asSequence()
                    .filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".jar") }
                    .flatMap { jarPath ->
                        runCatching<List<Properties>> {
                            JarFile(jarPath.toFile()).use { jar ->
                                jar.entries().asSequence().filter {
                                    it.name.startsWith("META-INF/strguard/artifacts/") &&
                                        it.name.endsWith(".properties")
                                }.map { marker ->
                                    Properties().apply { jar.getInputStream(marker).use(::load) }
                                }.toList()
                            }
                        }.getOrDefault(emptyList()).asSequence()
                    }
                    .toList()
            }
        val shrunkMarkers =
            markers.filter { marker ->
                marker.getProperty("stage") == "shrunk" &&
                    marker.getProperty("shrinkerId") == "compose-desktop-proguard"
            }
        assertEquals(2, shrunkMarkers.size)
        assertEquals(
            2,
            shrunkMarkers.map { marker -> marker.getProperty("loaderClass") }.toSet().size,
        )
    }

    private fun runner(vararg arguments: String): GradleRunner = GradleRunner.create()
        .withProjectDir(projectDirectory.toFile())
        .withPluginClasspath()
        .withArguments(*arguments, "--stacktrace")
        .forwardOutput()

    private fun includedBuildRunner(vararg arguments: String): GradleRunner = GradleRunner.create()
        .withProjectDir(projectDirectory.toFile())
        .withArguments(*arguments, "--stacktrace")
        .forwardOutput()

    private fun writeFile(relativePath: String, contents: String) {
        val file = projectDirectory.resolve(relativePath)
        Files.createDirectories(file.parent)
        Files.writeString(file, contents, StandardCharsets.UTF_8)
    }

    private fun hostNativeTarget(): JvmNativeTarget = JvmNativeTarget.detectHost(System.getProperty("os.name"), System.getProperty("os.arch"))

    private fun projectRootPath(): String = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().toString().replace("\\", "\\\\")
}

private const val SHRINKER_TEST_SEED =
    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
