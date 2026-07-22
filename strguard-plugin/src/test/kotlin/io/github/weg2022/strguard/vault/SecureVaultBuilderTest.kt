package io.github.weg2022.strguard.vault

import io.github.weg2022.strguard.JvmNativeTarget
import io.github.weg2022.strguard.crypto.CryptoPrimitives
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SecureVaultBuilderTest {
    @Test
    fun `protection distinguishes empty accepted and oversized UTF-16 strings`() {
        SecureVaultBuilder(
            TEST_RELEASE_SEED,
            MODULE_IDENTITY,
            INPUT_DIGEST,
            JvmNativeTarget.WINDOWS_X64,
        ).use { builder ->
            assertIs<VaultProtectionResult.Empty>(builder.protect("", "sample/Boundary#empty"))
            assertIs<VaultProtectionResult.Protected>(
                builder.protect("x".repeat(30_000), "sample/Boundary#accepted"),
            )
            assertIs<VaultProtectionResult.TooLarge>(
                builder.protect("x".repeat(30_001), "sample/Boundary#oversized"),
            )
        }
    }

    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `vault is deterministic diversified and authenticated`() {
        val first = buildVault(temporaryDirectory.resolve("first"), INPUT_DIGEST)
        val repeated = buildVault(temporaryDirectory.resolve("repeated"), INPUT_DIGEST)
        val changed = buildVault(temporaryDirectory.resolve("changed"), CHANGED_INPUT_DIGEST)
        val linux =
            buildVault(
                temporaryDirectory.resolve("linux"),
                INPUT_DIGEST,
                JvmNativeTarget.LINUX_GLIBC_X64,
            )

        assertContentEquals(first.vault, repeated.vault)
        assertContentEquals(first.nativeConfig, repeated.nativeConfig)
        assertEquals(first.model.bridge, repeated.model.bridge)
        assertNotEquals(first.model.bridge.internalClassName, changed.model.bridge.internalClassName)
        assertNotEquals(first.model.bridge.internalClassName, linux.model.bridge.internalClassName)
        assertEquals(
            "META-INF/strguard/native/linux-x86_64/${linux.model.bridge.nativeLibraryFileName}",
            linux.model.bridge.nativeLibraryResourcePath,
        )
        assertEquals(".so", linux.model.bridge.nativeLibraryFileName.takeLast(3))
        assertFalse(first.vault.asText().contains(FIRST_SECRET))
        assertFalse(first.vault.asText().contains(TEST_RELEASE_SEED))
        assertFalse(first.nativeConfig.asText().contains(FIRST_SECRET))
        assertFalse(first.nativeConfig.asText().contains(TEST_RELEASE_SEED))
        assertContentEquals(byteArrayOf('S'.code.toByte(), 'G'.code.toByte(), 'V'.code.toByte(), '3'.code.toByte()), first.vault.copyOfRange(0, 4))
        assertEquals(3, first.vault[4].toInt() and 0xff)

        val contents = parseVault(first.vault)
        assertEquals(2, contents.records.size)
        val firstCapability = first.references.first().capabilityBytes()
        val secondCapability = first.references.last().capabilityBytes()
        assertFalse(firstCapability.contentEquals(secondCapability))

        val firstRecord = contents.records.single { it.capability.contentEquals(firstCapability) }
        val secondRecord = contents.records.single { it.capability.contentEquals(secondCapability) }
        assertFalse(firstRecord.ciphertext.contentEquals(secondRecord.ciphertext))
        assertEquals(FIRST_SECRET, decrypt(firstRecord, contents.buildId, first.model))
        assertEquals(FIRST_SECRET, decrypt(secondRecord, contents.buildId, first.model))

        val tamperedCiphertext = firstRecord.ciphertext.copyOf()
        tamperedCiphertext[0] = (tamperedCiphertext[0].toInt() xor 1).toByte()
        assertFailsWith<AEADBadTagException> {
            decrypt(
                ParsedRecord(
                    firstRecord.capability,
                    firstRecord.gatewayIndex,
                    firstRecord.nonce,
                    firstRecord.plaintextLength,
                    tamperedCiphertext,
                ),
                contents.buildId,
                first.model,
            )
        }
        listOf(first, repeated, changed, linux).forEach { vault -> vault.model.close() }
    }

    @Test
    fun `vault v3 preserves Java UTF-16 code units losslessly`() {
        val secret = "prefix\u0000\uD800middle\uDC00\uD83D\uDE00suffix"
        val builder = SecureVaultBuilder(TEST_RELEASE_SEED, MODULE_IDENTITY, INPUT_DIGEST, JvmNativeTarget.WINDOWS_X64)
        val reference = builder.protect(secret, "sample/Secrets#utf16()Ljava/lang/String;:ldc:0").protectedReference()
        val model = builder.writeNativeInputs(temporaryDirectory.resolve("utf16"))
        val contents = parseVault(Files.readAllBytes(temporaryDirectory.resolve("utf16").resolve(VAULT_FILE_NAME)))
        val record = contents.records.single { it.capability.contentEquals(reference.capabilityBytes()) }

        assertEquals(secret.length, record.plaintextLength)
        assertEquals(secret.length * 2 + AUTHENTICATION_TAG_SIZE, record.ciphertext.size)
        assertContentEquals(secret.toCharArray(), decrypt(record, contents.buildId, model).toCharArray())
        model.close()
        builder.close()
    }

    @Test
    fun `close clears builder and Native model key material`() {
        val builder = SecureVaultBuilder(TEST_RELEASE_SEED, MODULE_IDENTITY, INPUT_DIGEST, JvmNativeTarget.WINDOWS_X64)
        assertIs<VaultProtectionResult.Protected>(
            builder.protect(FIRST_SECRET, "sample/Secrets#clear()Ljava/lang/String;:ldc:0"),
        )
        val model = builder.writeNativeInputs(temporaryDirectory.resolve("clear"))
        val masterKey =
            SecureVaultBuilder::class.java.getDeclaredField("masterKey").run {
                isAccessible = true
                get(builder) as ByteArray
            }
        val builderBuildId =
            SecureVaultBuilder::class.java.getDeclaredField("buildId").run {
                isAccessible = true
                get(builder) as ByteArray
            }
        val modelBuildId = model.buildId
        val shareBytes = model.keyShares.flatMap { share -> listOf(share.encoded, share.mask, share.order) }

        builder.close()
        model.close()

        assertTrue(masterKey.all { byte -> byte == 0.toByte() })
        assertTrue(builderBuildId.all { byte -> byte == 0.toByte() })
        assertTrue(modelBuildId.all { byte -> byte == 0.toByte() })
        assertTrue(shareBytes.all { bytes -> bytes.all { byte -> byte == 0.toByte() } })
        assertFailsWith<IllegalStateException> {
            builder.protect(FIRST_SECRET, "sample/Secrets#afterClose()Ljava/lang/String;:ldc:0")
        }
    }

    private fun buildVault(
        directory: Path,
        inputDigest: ByteArray,
        nativeTarget: JvmNativeTarget = JvmNativeTarget.WINDOWS_X64,
    ): BuiltVault {
        val builder = SecureVaultBuilder(TEST_RELEASE_SEED, MODULE_IDENTITY, inputDigest, nativeTarget)
        return try {
            val references =
                listOf(
                    builder.protect(FIRST_SECRET, "sample/Secrets#first()Ljava/lang/String;:ldc:0").protectedReference(),
                    builder.protect(FIRST_SECRET, "sample/Secrets#second()Ljava/lang/String;:ldc:0").protectedReference(),
                )
            val model = builder.writeNativeInputs(directory)
            BuiltVault(
                references = references,
                model = model,
                vault = Files.readAllBytes(directory.resolve(VAULT_FILE_NAME)),
                nativeConfig = Files.readAllBytes(directory.resolve(RUST_CONFIG_FILE_NAME)),
            )
        } finally {
            builder.close()
        }
    }

    private fun parseVault(bytes: ByteArray): ParsedVault {
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(VAULT_MAGIC.size).also(input::get)
        check(magic.contentEquals(VAULT_MAGIC))
        check(input.get().toInt() and 0xff == VAULT_VERSION)
        val buildId = ByteArray(BUILD_ID_SIZE).also(input::get)
        val records =
            List(input.int) {
                val bodyLength = input.int
                val bodyEnd = input.position() + bodyLength
                val capability = ByteArray(CAPABILITY_SIZE).also(input::get)
                val gatewayIndex = input.get().toInt() and 0xff
                val nonce = ByteArray(NONCE_SIZE).also(input::get)
                val plaintextLength = input.int
                val ciphertext = ByteArray(input.int).also(input::get)
                val paddingLength = input.short.toInt() and 0xffff
                input.position(input.position() + paddingLength)
                check(input.position() == bodyEnd)
                ParsedRecord(capability, gatewayIndex, nonce, plaintextLength, ciphertext)
            }
        check(!input.hasRemaining())
        return ParsedVault(buildId, records)
    }

    private fun decrypt(record: ParsedRecord, buildId: ByteArray, model: NativeVaultModel): String {
        val masterKey = ByteArray(KEY_SIZE)
        model.keyShares.forEach { share ->
            share.encoded.indices.forEach { encodedIndex ->
                val targetIndex = share.order[encodedIndex].toInt() and 0xff
                masterKey[targetIndex] =
                    (
                        masterKey[targetIndex].toInt() xor
                            share.encoded[encodedIndex].toInt() xor
                            share.mask[encodedIndex].toInt()
                        ).toByte()
            }
        }
        val recordKey =
            CryptoPrimitives.hkdfSha256(
                inputKeyMaterial = masterKey,
                salt = buildId,
                info = RECORD_KEY_LABEL + record.capability + byteArrayOf(record.gatewayIndex.toByte()),
                outputLength = KEY_SIZE,
            )
        return try {
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(recordKey, "ChaCha20"),
                IvParameterSpec(record.nonce),
            )
            cipher.updateAAD(
                VAULT_MAGIC +
                    byteArrayOf(VAULT_VERSION.toByte()) +
                    buildId +
                    record.capability +
                    byteArrayOf(record.gatewayIndex.toByte()) +
                    CryptoPrimitives.intLe(record.plaintextLength),
            )
            val plaintext = cipher.doFinal(record.ciphertext)
            check(plaintext.size == record.plaintextLength * 2)
            String(
                CharArray(record.plaintextLength) { index ->
                    val byteIndex = index * 2
                    (
                        (plaintext[byteIndex].toInt() and 0xff) or
                            ((plaintext[byteIndex + 1].toInt() and 0xff) shl 8)
                        ).toChar()
                },
            ).also { plaintext.fill(0) }
        } finally {
            masterKey.fill(0)
            recordKey.fill(0)
        }
    }
}

private fun VaultProtectionResult.protectedReference(): VaultReference = assertIs<VaultProtectionResult.Protected>(this).reference

private class BuiltVault(
    val references: List<VaultReference>,
    val model: NativeVaultModel,
    val vault: ByteArray,
    val nativeConfig: ByteArray,
)

private class ParsedVault(
    val buildId: ByteArray,
    val records: List<ParsedRecord>,
)

private class ParsedRecord(
    val capability: ByteArray,
    val gatewayIndex: Int,
    val nonce: ByteArray,
    val plaintextLength: Int,
    val ciphertext: ByteArray,
)

private fun VaultReference.capabilityBytes(): ByteArray = ByteBuffer.allocate(CAPABILITY_SIZE)
    .order(ByteOrder.BIG_ENDIAN)
    .putLong(capabilityHigh)
    .putLong(capabilityLow)
    .array()

private fun ByteArray.asText(): String = toString(StandardCharsets.ISO_8859_1)

private const val MODULE_IDENTITY = "io.github.weg2022:security-fixture::security-fixture"
private const val FIRST_SECRET = "first-sensitive-value"
private const val TEST_RELEASE_SEED =
    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
private const val BUILD_ID_SIZE = 16
private const val CAPABILITY_SIZE = 16
private const val NONCE_SIZE = 12
private const val KEY_SIZE = 32
private const val AUTHENTICATION_TAG_SIZE = 16
private val INPUT_DIGEST = CryptoPrimitives.sha256(CryptoPrimitives.utf8("fixture-input"))
private val CHANGED_INPUT_DIGEST = CryptoPrimitives.sha256(CryptoPrimitives.utf8("changed-fixture-input"))
private val RECORD_KEY_LABEL = CryptoPrimitives.utf8("strguard/v3/record-key")
