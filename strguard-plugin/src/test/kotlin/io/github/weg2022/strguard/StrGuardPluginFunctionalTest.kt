package io.github.weg2022.strguard

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.*
import java.net.URLClassLoader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
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
                strictStringCoverage.set(true)
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

                public static String identityFirst() {
                    return "shared-identity";
                }

                public static String identitySecond() {
                    return "shared-identity";
                }

                public static boolean preservesLiteralIdentity() {
                    return identityFirst() == identitySecond()
                        && identityFirst() == IdentityPeer.sharedIdentity()
                        && identityFirst() == "shared-identity"
                        && CONSTANT == "java-constant";
                }

                public static boolean preservesMonitorIdentity() {
                    synchronized ("shared-monitor") {
                        return Thread.holdsLock(IdentityPeer.monitorIdentity());
                    }
                }

                public static boolean concurrentFirstAccess() throws InterruptedException {
                    int count = 16;
                    String[] values = new String[count];
                    Thread[] threads = new Thread[count];
                    java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(count);
                    java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
                    for (int index = 0; index < count; index++) {
                        final int resultIndex = index;
                        threads[index] = new Thread(() -> {
                            ready.countDown();
                            try {
                                start.await();
                                values[resultIndex] = IdentityPeer.concurrentIdentity();
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                        });
                        threads[index].start();
                    }
                    if (!ready.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                        return false;
                    }
                    start.countDown();
                    for (Thread thread : threads) {
                        thread.join(10_000);
                        if (thread.isAlive()) {
                            return false;
                        }
                    }
                    for (int index = 1; index < count; index++) {
                        if (values[index] == null || values[index] != values[0]) {
                            return false;
                        }
                    }
                    return values[0] != null;
                }

                public static String specialUtf16() {
                    return "prefix\0\uD800middle\uDC00\uD83D\uDE00suffix";
                }

                public static String arrayValue(int index) {
                    String[] values = {
                        "array-zero",
                        "array-line-one\narray-line-two",
                        "array-unicode-\u4F60\u597D-\uD83D\uDE80"
                    };
                    return values[index];
                }

                public static String switchValue(int code) {
                    switch (code) {
                        case 0: return "switch-zero";
                        case 1: return "switch-one\twith-tab";
                        default: return "switch-default";
                    }
                }

                public static String lambdaValue() {
                    java.util.function.Supplier<String> supplier = () -> "lambda-sensitive-value";
                    return supplier.get();
                }

                public static String whitespaceAndControls() {
                    return " \t\r\n\1\2";
                }
            }
            """.trimIndent(),
        )
        writeFile(
            "src/main/java/sample/IdentityPeer.java",
            """
            package sample;

            public final class IdentityPeer {
                private IdentityPeer() {}

                public static String sharedIdentity() {
                    return "shared-identity";
                }

                public static String monitorIdentity() {
                    return "shared-monitor";
                }

                public static String concurrentIdentity() {
                    return "concurrent-first-access";
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
        assertFalse(
            directoryContainsText(projectDirectory.resolve(".gradle/configuration-cache"), TEST_RELEASE_SEED),
            "Gradle configuration cache contains the raw StrGuard release seed",
        )
        val nativeTarget = hostNativeTarget()
        val reportPath = projectDirectory.resolve("build/reports/strguard/main/summary.txt")
        val reportText = Files.readString(reportPath)
        val summary = Properties().apply {
            Files.newBufferedReader(reportPath, StandardCharsets.UTF_8).use(::load)
        }
        assertEquals(
            setOf(
                "schemaVersion",
                "enabled",
                "strictStringCoverage",
                "runtimeTarget",
                "inputClasses",
                "eligibleClasses",
                "matchedClasses",
                "skippedClasses",
                "stringCandidates",
                "protectedStrings",
                "skippedStrings",
                "strictViolations",
                "coverageUnknowns",
                "skippedEmptyStrings",
                "skippedOversizedStrings",
                "skippedAnnotationStrings",
                "skippedConstantDynamicStrings",
                "skippedDisabledStringConcats",
                "skippedUnsupportedStringConcats",
                "skippedUnsupportedInvokeDynamics",
                "skippedUnsupportedFieldStrings",
                "removedMetadata",
                "unmatchedKeepStringPackages",
                "unmatchedKeepMetadataPackages",
            ),
            summary.stringPropertyNames(),
        )
        assertEquals("1", summary.getProperty("schemaVersion"))
        assertEquals("true", summary.getProperty("enabled"))
        assertEquals("true", summary.getProperty("strictStringCoverage"))
        assertEquals(nativeTarget.rustTriple, summary.getProperty("runtimeTarget"))
        assertEquals("3", summary.getProperty("inputClasses"))
        assertEquals("3", summary.getProperty("eligibleClasses"))
        assertEquals("3", summary.getProperty("matchedClasses"))
        assertEquals("0", summary.getProperty("skippedClasses"))
        assertTrue(summary.getProperty("protectedStrings").toInt() > 0)
        assertEquals("0", summary.getProperty("skippedStrings"))
        assertEquals("0", summary.getProperty("strictViolations"))
        assertEquals("0", summary.getProperty("coverageUnknowns"))
        assertEquals("0", summary.getProperty("removedMetadata"))
        assertFalse(reportText.contains(TEST_RELEASE_SEED))
        assertFalse(reportText.contains("java-constant"))
        assertFalse(reportText.contains("prefix-"))
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
            assertEquals(true, example.getMethod("preservesLiteralIdentity").invoke(null))
            assertEquals(true, example.getMethod("preservesMonitorIdentity").invoke(null))
            assertEquals(true, example.getMethod("concurrentFirstAccess").invoke(null))
            val specialUtf16 = example.getMethod("specialUtf16").invoke(null) as String
            assertContentEquals(
                "prefix\u0000\uD800middle\uDC00\uD83D\uDE00suffix".toCharArray(),
                specialUtf16.toCharArray(),
            )
            val arrayValue = example.getMethod("arrayValue", Int::class.javaPrimitiveType)
            assertEquals("array-zero", arrayValue.invoke(null, 0))
            assertEquals("array-line-one\narray-line-two", arrayValue.invoke(null, 1))
            assertEquals("array-unicode-\u4F60\u597D-\uD83D\uDE80", arrayValue.invoke(null, 2))
            val switchValue = example.getMethod("switchValue", Int::class.javaPrimitiveType)
            assertEquals("switch-zero", switchValue.invoke(null, 0))
            assertEquals("switch-one\twith-tab", switchValue.invoke(null, 1))
            assertEquals("switch-default", switchValue.invoke(null, 9))
            assertEquals("lambda-sensitive-value", example.getMethod("lambdaValue").invoke(null))
            assertEquals(" \t\r\n\u0001\u0002", example.getMethod("whitespaceAndControls").invoke(null))
        }

        val artifact = projectDirectory.resolve("build/libs/java-consumer.jar")
        JarFile(artifact.toFile()).use { jar ->
            val entry = assertNotNull(jar.getJarEntry("sample/JavaExample.class"))
            val contents = jar.getInputStream(entry).readBytes().toString(StandardCharsets.ISO_8859_1)
            assertFalse(contents.contains("java-constant"))
            assertTrue(
                jar.entries().asSequence().any {
                    it.name.startsWith("io/github/weg2022/strguard/generated/L") && it.name.endsWith(".class")
                },
            )
            assertNull(jar.getJarEntry("io/github/weg2022/strguard/runtime/NativeLibraryLoader.class"))
            assertTrue(
                jar.entries().asSequence().any {
                    it.name.startsWith("META-INF/strguard/native/${nativeTarget.packagingDirectory}/") &&
                        it.name.endsWith(nativeTarget.libraryExtension)
                },
            )
            val markerEntry =
                assertNotNull(
                    jar.entries().asSequence().singleOrNull {
                        it.name.startsWith("META-INF/strguard/artifacts/") && it.name.endsWith(".properties")
                    },
                )
            val marker = Properties().apply { jar.getInputStream(markerEntry).use(::load) }
            assertEquals("protected", marker.getProperty("stage"))
            assertEquals(nativeTarget.rustTriple, marker.getProperty("runtimeTarget"))
            assertEquals(8, marker.getProperty("gatewayNames").split(',').size)
            assertTrue(marker.getProperty("loaderClass").startsWith("io/github/weg2022/strguard/generated/L"))
            val artifactId = assertNotNull(marker.getProperty("artifactId"))
            val embeddedRules = assertNotNull(jar.getJarEntry("META-INF/proguard/strguard-$artifactId.pro"))
            val rulesText = jar.getInputStream(embeddedRules).bufferedReader().use { it.readText() }
            assertTrue(rulesText.contains("generated.B*"))
            assertTrue(rulesText.contains("generated.L*"))
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

        tamperEmbeddedVault(
            nativeLibrary,
            projectDirectory.resolve("build/strguard/native-input/main/vault.bin"),
        )
        URLClassLoader(
            arrayOf(transformedClasses.toUri().toURL(), nativeResources.toUri().toURL()),
            ClassLoader.getPlatformClassLoader(),
        ).use { loader ->
            val failure = assertFails { Class.forName("sample.JavaExample", true, loader) }
            val messages = generateSequence(failure) { cause -> cause.cause }.mapNotNull(Throwable::message).toList()
            assertTrue(
                messages.any { message -> message.contains("No valid StrGuard Native runtime resource container") },
                "Expected Native resource integrity failure, got: $messages",
            )
        }
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
                strictStringCoverage.set(true)
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

                fun preservesLiteralIdentity(): Boolean =
                    localIdentity() === KotlinIdentityPeer.sharedIdentity() &&
                        localIdentity() === "kotlin-shared-identity"

                private fun localIdentity(): String = "kotlin-shared-identity"

                fun collectionValue(index: Int): String {
                    val values = arrayOf(
                        "kotlin-array-zero",
                        "kotlin-line-one\nkotlin-line-two",
                        "kotlin-unicode-\u4F60\u597D-\uD83D\uDE80",
                    )
                    return values[index]
                }

                fun whenValue(code: Int): String = when (code) {
                    0 -> "kotlin-when-zero"
                    1 -> "kotlin-when-one\twith-tab"
                    else -> "kotlin-when-default"
                }

                fun lambdaValue(): String = { "kotlin-lambda-sensitive-value" }()

                fun specialUtf16(): String = "prefix\u0000\uD800middle\uDC00\uD83D\uDE00suffix"
            }

            object KotlinIdentityPeer {
                fun sharedIdentity(): String = "kotlin-shared-identity"
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
            assertEquals(true, example.getMethod("preservesLiteralIdentity").invoke(instance))
            val collectionValue = example.getMethod("collectionValue", Int::class.javaPrimitiveType)
            assertEquals("kotlin-array-zero", collectionValue.invoke(instance, 0))
            assertEquals("kotlin-line-one\nkotlin-line-two", collectionValue.invoke(instance, 1))
            assertEquals("kotlin-unicode-\u4F60\u597D-\uD83D\uDE80", collectionValue.invoke(instance, 2))
            val whenValue = example.getMethod("whenValue", Int::class.javaPrimitiveType)
            assertEquals("kotlin-when-zero", whenValue.invoke(instance, 0))
            assertEquals("kotlin-when-one\twith-tab", whenValue.invoke(instance, 1))
            assertEquals("kotlin-when-default", whenValue.invoke(instance, 9))
            assertEquals("kotlin-lambda-sensitive-value", example.getMethod("lambdaValue").invoke(instance))
            assertContentEquals(
                "prefix\u0000\uD800middle\uDC00\uD83D\uDE00suffix".toCharArray(),
                (example.getMethod("specialUtf16").invoke(instance) as String).toCharArray(),
            )

            val topLevel = Class.forName("sample.KotlinExampleKt", true, loader)
            assertEquals("kotlin-constant", topLevel.getField("EXPOSED").get(null))
        }
    }

    @Test
    fun `preserves interned literal identity across protected modules`() {
        writeFile(
            "settings.gradle.kts",
            """
            rootProject.name = "identity-modules"
            include("one", "two")
            """.trimIndent(),
        )
        listOf("one", "two").forEach { module ->
            writeFile(
                "$module/build.gradle.kts",
                """
                plugins {
                    java
                    id("io.github.weg2022.strguard")
                }

                strGuard {
                    releaseSeedHex.set("$TEST_RELEASE_SEED")
                    stringGuardPackages.set(listOf("sample"))
                }
                """.trimIndent(),
            )
            val className = module.replaceFirstChar(Char::uppercaseChar)
            writeFile(
                "$module/src/main/java/sample/$module/${className}Value.java",
                """
                package sample.$module;

                public final class ${className}Value {
                    private ${className}Value() {}

                    public static String reveal() {
                        return "cross-module-identity";
                    }
                }
                """.trimIndent(),
            )
        }

        val result = runner(":one:classes", ":two:classes").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":one:buildStrGuardNativeMain")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":two:buildStrGuardNativeMain")?.outcome)
        URLClassLoader(
            arrayOf(
                projectDirectory.resolve("one/build/strguard/classes/main").toUri().toURL(),
                projectDirectory.resolve("one/build/strguard/native-resources/main").toUri().toURL(),
                projectDirectory.resolve("two/build/strguard/classes/main").toUri().toURL(),
                projectDirectory.resolve("two/build/strguard/native-resources/main").toUri().toURL(),
            ),
            ClassLoader.getPlatformClassLoader(),
        ).use { loader ->
            val one = Class.forName("sample.one.OneValue", true, loader).getMethod("reveal").invoke(null) as String
            val two = Class.forName("sample.two.TwoValue", true, loader).getMethod("reveal").invoke(null) as String
            assertSame(one, two)
        }
    }

    private fun runner(vararg arguments: String): GradleRunner = GradleRunner.create()
        .withProjectDir(projectDirectory.toFile())
        .withPluginClasspath()
        .withArguments(*arguments, "--stacktrace")
        .forwardOutput()

    private fun writeFile(relativePath: String, contents: String) {
        val file = projectDirectory.resolve(relativePath)
        Files.createDirectories(file.parent)
        Files.writeString(file, contents, StandardCharsets.UTF_8)
    }

    private fun classContains(classFile: Path, value: String): Boolean = Files.readAllBytes(classFile).toString(StandardCharsets.ISO_8859_1).contains(value)

    private fun directoryContainsText(directory: Path, value: String): Boolean {
        if (!Files.isDirectory(directory)) return false
        return Files.walk(directory).use { paths ->
            paths.iterator().asSequence()
                .filter(Files::isRegularFile)
                .any { file ->
                    Files.readAllBytes(file).toString(StandardCharsets.ISO_8859_1).contains(value)
                }
        }
    }

    private fun findNativeLibrary(nativeResources: Path, nativeTarget: JvmNativeTarget): Path = Files.walk(nativeResources).use { paths ->
        paths.filter {
            Files.isRegularFile(it) && it.fileName.toString().endsWith(nativeTarget.libraryExtension)
        }
            .findFirst()
            .orElseThrow { AssertionError("No StrGuard Native library was generated") }
    }

    private fun hostNativeTarget(): JvmNativeTarget = JvmNativeTarget.detectHost(
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
                            (
                                encodedShares[shareIndex][encodedIndex].toInt() xor
                                    masks[shareIndex][encodedIndex].toInt()
                                ).toByte()
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

    private fun tamperEmbeddedVault(nativeLibrary: Path, vaultFile: Path) {
        val vault = Files.readAllBytes(vaultFile)
        val modifiedVault = vault.copyOf()
        val vaultBuffer = ByteBuffer.wrap(modifiedVault).order(ByteOrder.LITTLE_ENDIAN)
        vaultBuffer.position(VAULT_RECORD_COUNT_OFFSET)
        val recordCount = vaultBuffer.int
        check(recordCount > 0)
        repeat(recordCount) {
            val bodyLength = vaultBuffer.int
            val bodyStart = vaultBuffer.position()
            vaultBuffer.position(bodyStart + VAULT_RECORD_CIPHERTEXT_LENGTH_OFFSET)
            val ciphertextLength = vaultBuffer.int
            check(ciphertextLength > 0)
            val ciphertextStart = vaultBuffer.position()
            modifiedVault[ciphertextStart] = (modifiedVault[ciphertextStart].toInt() xor 1).toByte()
            vaultBuffer.position(bodyStart + bodyLength)
        }

        val nativeBytes = Files.readAllBytes(nativeLibrary)
        val vaultOffset = nativeBytes.indexOfSequence(vault)
        check(vaultOffset >= 0) { "Native library does not contain the generated vault" }
        modifiedVault.copyInto(nativeBytes, vaultOffset)
        Files.write(nativeLibrary, nativeBytes)
    }
}

private class GeneratedKeyMaterial(
    val shares: List<ByteArray>,
    val masterKey: ByteArray,
)

private fun ByteArray.containsSequence(needle: ByteArray): Boolean = indexOfSequence(needle) >= 0

private fun ByteArray.indexOfSequence(needle: ByteArray): Int {
    if (needle.isEmpty() || needle.size > size) {
        return -1
    }
    return (0..size - needle.size).firstOrNull { offset ->
        needle.indices.all { index -> this[offset + index] == needle[index] }
    }
        ?: -1
}

private const val TEST_RELEASE_SEED =
    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
private const val KEY_SHARE_COUNT = 4
private const val KEY_SIZE = 32
private const val JAVA_11_CLASS_VERSION = 55
private const val VAULT_RECORD_COUNT_OFFSET = 4 + 1 + 16
private const val VAULT_RECORD_CIPHERTEXT_LENGTH_OFFSET = 16 + 1 + 12 + 4
private val HEX_BYTE = Regex("0x([0-9a-f]{2})")
