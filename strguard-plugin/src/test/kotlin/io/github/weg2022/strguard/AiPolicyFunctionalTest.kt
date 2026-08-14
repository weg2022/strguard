package io.github.weg2022.strguard

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.Attribute
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@EnabledIfEnvironmentVariable(named = "STRGUARD_FUNCTIONAL_TEST", matches = "true")
class AiPolicyFunctionalTest {
    private lateinit var projectDirectory: Path

    @BeforeEach
    fun setUp() {
        projectDirectory = Files.createTempDirectory("strguard-ai-policy-")
    }

    @AfterEach
    fun tearDown() {
        // Windows 上 native 构建会在私有 HOME 下创建 WinINet 缓存文件(Content.IE5),
        // 文件短暂被锁时 JUnit @TempDir 清理失败并把测试判红(既有环境问题,见
        // BuildNativeRuntimeTask 的 HOME/USERPROFILE 设置);这里 best-effort 删除,
        // 残留交给系统临时目录回收。
        try {
            projectDirectory.toFile().deleteRecursively()
        } catch (_: IOException) {
            // 忽略清理失败,测试结果不受影响。
        }
    }

    @Test
    fun `enabled policy writes the marker into the protected jar`() {
        writeFile("settings.gradle.kts", "rootProject.name = \"ai-policy-consumer\"")
        writeFile(
            "build.gradle.kts",
            """
            plugins {
                java
                id("io.github.weg2022.strguard")
            }

            strGuard {
                releaseSeedHex.set("$AI_POLICY_TEST_SEED")
                aiPolicyEnabled.set(true)
                aiPolicyContact.set("legal@example.com")
                aiPolicyExceptions.set(listOf("authorized security research"))
            }
            """.trimIndent(),
        )
        writeFile(
            "src/main/java/sample/ProtectedApp.java",
            """
            package sample;

            public final class ProtectedApp {
                public static final String CONSTANT = "policy-field-value";

                public static String reveal() {
                    return "policy-marker-sensitive-value";
                }
            }
            """.trimIndent(),
        )

        val result = runner("jar").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":transformStrGuardMain")?.outcome)
        val jar = projectDirectory.resolve("build/libs/ai-policy-consumer.jar")
        JarFile(jar.toFile()).use { archive ->
            val applicationClass = archive.getInputStream(archive.getJarEntry("sample/ProtectedApp.class"))
                .use { it.readBytes() }
            val probe = probe(applicationClass)
            assertTrue(probe.classAnnotationPresent, "protected jar classes must carry the class-level policy marker")
            assertTrue(probe.methodAnnotationPresent, "protected jar methods must carry the method-level policy marker")
            assertTrue(probe.fieldAnnotationPresent, "protected jar fields must carry the field-level policy marker")
            val fieldValue = probe.fieldAnnotationValue.orEmpty()
            assertTrue(fieldValue.startsWith("Policy: reverse-engineering-prohibition\n"), fieldValue)
            assertTrue(fieldValue.contains("Declared-By: :ai-policy-consumer:"), fieldValue)
            assertFalse(fieldValue.contains('{'), "policy must be plain text, not JSON")
            assertTrue(probe.attributePresent, "protected jar classes must carry the redundant attribute")
            listOf(
                "io/github/weg2022/strguard/annotation/ReverseEngineeringPolicy.class",
                "io/github/weg2022/strguard/annotation/MethodReverseEngineeringPolicy.class",
                "io/github/weg2022/strguard/annotation/FieldReverseEngineeringPolicy.class",
            ).forEach { annotationClass ->
                assertTrue(archive.getJarEntry(annotationClass) != null, "$annotationClass must ship with the artifact")
            }
        }
    }

    @Test
    fun `marker survives ProGuard shrinking when keep attributes rule is applied`() {
        writeFile("settings.gradle.kts", "rootProject.name = \"ai-policy-shrinker-consumer\"")
        writeFile(
            "build.gradle.kts",
            """
            import proguard.gradle.ProGuardTask

            buildscript {
                repositories { mavenCentral() }
                dependencies { classpath("com.guardsquare:proguard-gradle:7.7.0") }
            }

            plugins {
                java
                id("io.github.weg2022.strguard")
            }

            strGuard {
                releaseSeedHex.set("$AI_POLICY_TEST_SEED")
                aiPolicyEnabled.set(true)
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
                    return "proguard-policy-sensitive-value";
                }
            }
            """.trimIndent(),
        )

        val result = runner("assertVerifiedProGuardJar").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyStrGuardMainShrunkArtifact")?.outcome)
        val verifiedJar = projectDirectory.resolve("build/strguard/shrinker/main/verified.jar")
        val markerSurvived =
            JarFile(verifiedJar.toFile()).use { archive ->
                archive.entries().asSequence()
                    .filter { entry -> entry.name.endsWith(".class") }
                    .any { entry ->
                        val probe = probe(archive.getInputStream(entry).use { it.readBytes() })
                        probe.classAnnotationPresent || probe.methodAnnotationPresent || probe.fieldAnnotationPresent
                    }
            }
        assertTrue(markerSurvived, "policy annotations must survive shrinking with the shipped keep rule")
    }

    @Test
    fun `verifier fails when a custom shrinker configuration strips the marker`() {
        writeFile("settings.gradle.kts", "rootProject.name = \"ai-policy-strip-consumer\"")
        writeFile(
            "build.gradle.kts",
            """
            import proguard.gradle.ProGuardTask

            buildscript {
                repositories { mavenCentral() }
                dependencies { classpath("com.guardsquare:proguard-gradle:7.7.0") }
            }

            plugins {
                java
                id("io.github.weg2022.strguard")
            }

            strGuard {
                releaseSeedHex.set("$AI_POLICY_TEST_SEED")
                aiPolicyEnabled.set(true)
            }

            val artifact = strGuardArtifacts.jvm("main")
            val rawShrunkJar = layout.buildDirectory.file("shrinker/proguard-raw.jar")
            val shrink = tasks.register<ProGuardTask>("proguardMain") {
                dependsOn(artifact.protectedJar)
                injars(artifact.protectedJar.get().asFile)
                outjars(rawShrunkJar.get().asFile)
                // 覆盖 shipped rules:保留 bridge/loader 但不保留 RuntimeInvisibleAnnotations,
                // 模拟用户 shrinker 配置剥掉策略标记的场景。
                configuration(file("stripping.pro"))
                libraryjars(
                    mapOf("jarfilter" to "!**.jar", "filter" to "!module-info.class"),
                    file("${'$'}{System.getProperty("java.home")}/jmods/java.base.jmod"),
                )
                keep("public class sample.ProtectedApp { public static java.lang.String reveal(); }")
                dontwarn()
                dontnote()
                allowaccessmodification()
            }
            val verifiedJar = artifact.verifyShrunkJar(
                shrink.map { rawShrunkJar.get() },
                "proguard:7.7.0",
            )
            tasks.register("assertStripFails") {
                dependsOn(verifiedJar)
                doLast { check(verifiedJar.get().asFile.isFile) }
            }
            """.trimIndent(),
        )
        writeFile(
            "stripping.pro",
            """
            -keep class io.github.weg2022.strguard.generated.B* { *; }
            -keep class io.github.weg2022.strguard.generated.L* { *; }
            """.trimIndent(),
        )
        writeFile(
            "src/main/java/sample/ProtectedApp.java",
            """
            package sample;

            public final class ProtectedApp {
                public static String reveal() {
                    return "proguard-strip-sensitive-value";
                }
            }
            """.trimIndent(),
        )

        val failure = assertFailsWith<org.gradle.testkit.runner.UnexpectedBuildFailure> {
            runner("assertStripFails").build()
        }
        assertTrue(
            failure.message.orEmpty().contains("reverse-engineering prohibition marker"),
            "verifier must fail with a marker-specific message",
        )
    }

    private class Probe(
        val classAnnotationPresent: Boolean,
        val methodAnnotationPresent: Boolean,
        val fieldAnnotationPresent: Boolean,
        val fieldAnnotationValue: String?,
        val attributePresent: Boolean,
    )

    private fun probe(bytes: ByteArray): Probe {
        var classAnnotationPresent = false
        var methodAnnotationPresent = false
        var fieldAnnotationPresent = false
        var fieldAnnotationValue: String? = null
        var attributePresent = false
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? {
                    if (descriptor == AiPolicyMarker.ANNOTATION_DESCRIPTOR) classAnnotationPresent = true
                    return null
                }

                override fun visitMethod(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    // 不委托 super:ClassVisitor 默认返回 null,委托会丢失所有方法级回调。
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitAnnotation(annotationDescriptor: String?, visible: Boolean): AnnotationVisitor? {
                            if (annotationDescriptor == AiPolicyMarker.METHOD_ANNOTATION_DESCRIPTOR) {
                                methodAnnotationPresent = true
                            }
                            return null
                        }
                    }
                }

                override fun visitField(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    value: Any?,
                ): FieldVisitor? {
                    // 不委托 super:ClassVisitor 默认返回 null,委托会丢失所有字段级回调。
                    return object : FieldVisitor(Opcodes.ASM9) {
                        override fun visitAnnotation(annotationDescriptor: String?, visible: Boolean): AnnotationVisitor? {
                            if (annotationDescriptor == AiPolicyMarker.FIELD_ANNOTATION_DESCRIPTOR) {
                                fieldAnnotationPresent = true
                                return object : AnnotationVisitor(Opcodes.ASM9) {
                                    override fun visit(name: String?, value: Any?) {
                                        if (name == AiPolicyMarker.ELEMENT_NAME && value is String) {
                                            fieldAnnotationValue = value
                                        }
                                    }
                                }
                            }
                            return null
                        }
                    }
                }

                override fun visitAttribute(attribute: Attribute?) {
                    if (attribute?.type == AiPolicyMarker.ATTRIBUTE_NAME) attributePresent = true
                }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        return Probe(classAnnotationPresent, methodAnnotationPresent, fieldAnnotationPresent, fieldAnnotationValue, attributePresent)
    }

    private fun runner(vararg arguments: String): GradleRunner = gradleRunnerFor(projectDirectory, *arguments, withPluginClasspath = true)

    private fun writeFile(relativePath: String, contents: String) {
        val target = projectDirectory.resolve(relativePath)
        Files.createDirectories(target.parent)
        Files.writeString(target, contents)
    }
}

private const val AI_POLICY_TEST_SEED =
    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
