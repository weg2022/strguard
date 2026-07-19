package io.github.weg2022.strguard

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.*
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.test.*

class StrGuardPluginFunctionalTest {
    @TempDir
    lateinit var projectDirectory: Path

    @Test
    fun `transforms Java constants and Java 9 string concatenation`() {
        writeFile(
            "settings.gradle.kts",
            "rootProject.name = \"java-consumer\"",
        )
        writeFile(
            "build.gradle.kts",
            """
            import org.gradle.api.tasks.compile.JavaCompile

            plugins {
                java
                id("io.github.weg2022.strguard")
            }

            tasks.withType<JavaCompile>().configureEach {
                options.release.set(11)
            }

            strGuard {
                releaseSeedHex.set("$TEST_RELEASE_SEED")
                stringGuardPackages.set(listOf("sample"))
                consoleOutput.set(true)
            }
            """.trimIndent(),
        )
        writeFile(
            "src/main/java/sample/JavaExample.java",
            """
            package sample;

            public class JavaExample {
                public static final String CONSTANT = "java-constant";

                public static String reveal(String value) {
                    return "prefix-" + value + "-suffix";
                }

                public static String numeric(boolean useInteger, String value) {
                    Number number = useInteger ? Integer.valueOf(1) : Long.valueOf(2L);
                    return "number=" + number.intValue() + value;
                }
            }
            """.trimIndent(),
        )
        writeFile(
            "src/main/java/sample/KeptExample.java",
            """
            package sample;

            import io.github.weg2022.strguard.annotation.KeepString;

            @KeepString
            public class KeptExample {
                public static String reveal() {
                    return "kept-java-value";
                }
            }
            """.trimIndent(),
        )

        val result = runner("classes", "jar", "--configuration-cache").build()
        val cachedResult = runner("classes", "jar", "--configuration-cache").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":transformStrGuardMain")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":buildStrGuardNativeMain")?.outcome)
        assertTrue(cachedResult.output.contains("Reusing configuration cache."))
        val transformedClasses = projectDirectory.resolve("build/strguard/classes/main")
        val nativeResources = projectDirectory.resolve("build/strguard/native-resources/main")
        val transformedExample = transformedClasses.resolve("sample/JavaExample.class")
        assertEquals(
            JAVA_11_CLASS_VERSION,
            ClassReader(Files.readAllBytes(transformedExample)).readShort(6).toInt() and 0xffff,
        )
        assertFalse(classContains(transformedExample, "java-constant"))
        assertFalse(classContains(transformedExample, "prefix-"))
        assertFalse(classContains(transformedExample, TEST_RELEASE_SEED))
        assertTrue(classContains(transformedClasses.resolve("sample/KeptExample.class"), "kept-java-value"))

        URLClassLoader(
            arrayOf(transformedClasses.toUri().toURL(), nativeResources.toUri().toURL()),
            ClassLoader.getPlatformClassLoader(),
        ).use { loader ->
            val example = Class.forName("sample.JavaExample", true, loader)
            val reveal = example.getMethod("reveal", String::class.java)
            assertEquals("prefix-value-suffix", reveal.invoke(null, "value"))
            val numeric = example.getMethod("numeric", Boolean::class.javaPrimitiveType, String::class.java)
            assertEquals("number=1x", numeric.invoke(null, true, "x"))
            assertEquals("number=2x", numeric.invoke(null, false, "x"))
            assertEquals("java-constant", example.getField("CONSTANT").get(null))
        }

        val artifact = projectDirectory.resolve("build/libs/java-consumer.jar")
        val nativeTarget = hostNativeTarget()
        JarFile(artifact.toFile()).use { jar ->
            val entry = assertNotNull(jar.getJarEntry("sample/JavaExample.class"))
            val contents = jar.getInputStream(entry).readBytes().toString(StandardCharsets.ISO_8859_1)
            assertFalse(contents.contains("java-constant"))
            assertNotNull(jar.getJarEntry("io/github/weg2022/strguard/runtime/NativeLibraryLoader.class"))
            assertTrue(
                jar.entries().asSequence().any {
                    it.name.startsWith("META-INF/strguard/native/${nativeTarget.resourceDirectory}/") &&
                            it.name.endsWith(nativeTarget.libraryExtension)
                },
            )
            assertTrue(jar.getJarEntry("io/github/weg2022/strguard/runtime/StrGuardRuntime.class") == null)
        }
        val nativeLibrary = findNativeLibrary(nativeResources, nativeTarget)
        assertFalse(classContains(nativeLibrary, "java-constant"))
        assertFalse(classContains(nativeLibrary, "prefix-"))
        assertFalse(classContains(nativeLibrary, TEST_RELEASE_SEED))
        val keyMaterial = readGeneratedKeyMaterial(
            projectDirectory.resolve("build/strguard/native-input/main/native_config.rs"),
        )
        val nativeBytes = Files.readAllBytes(nativeLibrary)
        assertFalse(nativeBytes.containsSequence(keyMaterial.masterKey))
        keyMaterial.shares.forEach { share -> assertFalse(nativeBytes.containsSequence(share)) }
    }

    @Test
    fun `transforms Kotlin JVM output and makes annotations available during compilation`() {
        writeFile(
            "settings.gradle.kts",
            """
            pluginManagement {
                repositories {
                    gradlePluginPortal()
                    mavenCentral()
                }
            }
            rootProject.name = "kotlin-consumer"
            """.trimIndent(),
        )
        writeFile(
            "build.gradle.kts",
            """
            plugins {
                kotlin("jvm") version "2.1.21"
                id("io.github.weg2022.strguard")
            }

            repositories {
                mavenCentral()
            }

            strGuard {
                releaseSeedHex.set("$TEST_RELEASE_SEED")
                stringGuardPackages.set(listOf("sample"))
                consoleOutput.set(true)
                removeMetadata.set(true)
                removeMetadataPackages.set(listOf("sample"))
            }
            """.trimIndent(),
        )
        writeFile(
            "src/main/kotlin/sample/KotlinExample.kt",
            """
            package sample

            import io.github.weg2022.strguard.annotation.KeepString
            import io.github.weg2022.strguard.annotation.KeepMetadata

            const val EXPOSED = "kotlin-constant"

            class KotlinExample {
                fun reveal(value: String): String = "prefix-${'$'}value-suffix"
            }

            @KeepString
            class KeptExample {
                fun reveal(): String = "kept-kotlin-value"
            }

            @KeepMetadata
            class MetadataKeptExample
            """.trimIndent(),
        )

        val result = runner("classes").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":transformStrGuardMain")?.outcome)
        val transformedClasses = projectDirectory.resolve("build/strguard/classes/main")
        val nativeResources = projectDirectory.resolve("build/strguard/native-resources/main")
        assertFalse(classContains(transformedClasses.resolve("sample/KotlinExampleKt.class"), "kotlin-constant"))
        assertFalse(classContains(transformedClasses.resolve("sample/KotlinExample.class"), "prefix-"))
        assertTrue(classContains(transformedClasses.resolve("sample/KeptExample.class"), "kept-kotlin-value"))
        assertFalse(hasClassAnnotation(transformedClasses.resolve("sample/KotlinExample.class"), "Lkotlin/Metadata;"))
        assertTrue(hasClassAnnotation(transformedClasses.resolve("sample/MetadataKeptExample.class"), "Lkotlin/Metadata;"))

        URLClassLoader(
            arrayOf(transformedClasses.toUri().toURL(), nativeResources.toUri().toURL()),
            javaClass.classLoader,
        ).use { loader ->
            val example = Class.forName("sample.KotlinExample", true, loader)
            val instance = example.getConstructor().newInstance()
            assertEquals("prefix-value-suffix", example.getMethod("reveal", String::class.java).invoke(instance, "value"))

            val topLevel = Class.forName("sample.KotlinExampleKt", true, loader)
            assertEquals("kotlin-constant", topLevel.getField("EXPOSED").get(null))
        }
    }

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDirectory.toFile())
            .withPluginClasspath()
            .withArguments(*arguments, "--stacktrace")
            .forwardOutput()

    private fun writeFile(relativePath: String, contents: String) {
        val file = projectDirectory.resolve(relativePath)
        Files.createDirectories(file.parent)
        Files.writeString(file, contents, StandardCharsets.UTF_8)
    }

    private fun classContains(classFile: Path, value: String): Boolean =
        Files.readAllBytes(classFile).toString(StandardCharsets.ISO_8859_1).contains(value)

    private fun findNativeLibrary(nativeResources: Path, nativeTarget: NativeTarget): Path =
        Files.walk(nativeResources).use { paths ->
            paths.filter {
                Files.isRegularFile(it) && it.fileName.toString().endsWith(nativeTarget.libraryExtension)
            }
                .findFirst()
                .orElseThrow { AssertionError("No StrGuard Native library was generated") }
        }

    private fun hostNativeTarget(): NativeTarget =
        NativeTarget.detectHost(
            System.getProperty("os.name"),
            System.getProperty("os.arch"),
        )

    private fun readGeneratedKeyMaterial(nativeConfig: Path): GeneratedKeyMaterial {
        val source = Files.readString(nativeConfig)
        val encodedShares = readRustMatrix(source, "ENCODED_KEY_SHARES")
        val masks = readRustMatrix(source, "KEY_SHARE_MASKS")
        val orders = readRustMatrix(source, "KEY_SHARE_ORDERS")
        val shares =
            List(KEY_SHARE_COUNT) { shareIndex ->
                ByteArray(KEY_SIZE).also { share ->
                    repeat(KEY_SIZE) { encodedIndex ->
                        val targetIndex = orders[shareIndex][encodedIndex].toInt() and 0xff
                        share[targetIndex] =
                            (encodedShares[shareIndex][encodedIndex].toInt() xor
                                    masks[shareIndex][encodedIndex].toInt()).toByte()
                    }
                }
            }
        val masterKey = ByteArray(KEY_SIZE)
        shares.forEach { share ->
            masterKey.indices.forEach { index ->
                masterKey[index] = (masterKey[index].toInt() xor share[index].toInt()).toByte()
            }
        }
        return GeneratedKeyMaterial(shares, masterKey)
    }

    private fun readRustMatrix(source: String, name: String): List<ByteArray> {
        val section =
            source.substringAfter("pub static $name")
                .substringAfter("= [")
                .substringBefore("\n];", missingDelimiterValue = "")
        check(section.isNotEmpty())
        val bytes =
            HEX_BYTE.findAll(section)
                .map { match -> match.groupValues[1].toInt(16).toByte() }
                .toList()
        check(bytes.size == KEY_SHARE_COUNT * KEY_SIZE)
        return bytes.chunked(KEY_SIZE).map { row -> row.toByteArray() }
    }

    private fun hasClassAnnotation(classFile: Path, descriptor: String): Boolean {
        var found = false
        ClassReader(Files.readAllBytes(classFile)).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitAnnotation(annotationDescriptor: String?, visible: Boolean): AnnotationVisitor? {
                    if (annotationDescriptor == descriptor) {
                        found = true
                    }
                    return null
                }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        return found
    }
}

private class GeneratedKeyMaterial(
    val shares: List<ByteArray>,
    val masterKey: ByteArray,
)

private fun ByteArray.containsSequence(needle: ByteArray): Boolean {
    if (needle.isEmpty() || needle.size > size) {
        return false
    }
    return (0..size - needle.size).any { offset ->
        needle.indices.all { index -> this[offset + index] == needle[index] }
    }
}

private const val TEST_RELEASE_SEED =
    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
private const val KEY_SHARE_COUNT = 4
private const val KEY_SIZE = 32
private const val JAVA_11_CLASS_VERSION = 55
private val HEX_BYTE = Regex("0x([0-9a-f]{2})")
