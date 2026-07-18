package io.github.weg2022.strguard.vault

import io.github.weg2022.strguard.NativeTarget
import io.github.weg2022.strguard.crypto.CryptoPrimitives
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

internal class SecureVaultBuilder(
    releaseSeedHex: String,
    moduleIdentity: String,
    inputDigest: ByteArray,
    private val nativeTarget: NativeTarget,
) {
    private val releaseSeed = CryptoPrimitives.parseHex256(releaseSeedHex)
    private val moduleBytes = CryptoPrimitives.utf8(moduleIdentity)
    private val targetBytes = CryptoPrimitives.utf8(nativeTarget.rustTriple)
    private val buildId =
        CryptoPrimitives.hmacSha256(
            releaseSeed,
            BUILD_ID_LABEL,
            moduleBytes,
            byteArrayOf(0),
            targetBytes,
            inputDigest,
        ).copyOf(BUILD_ID_SIZE)
    private val masterKey =
        CryptoPrimitives.hkdfSha256(
            inputKeyMaterial = releaseSeed,
            salt = inputDigest,
            info = BUILD_KEY_LABEL + moduleBytes + byteArrayOf(0) + targetBytes,
            outputLength = KEY_SIZE,
        )
    private val records = mutableListOf<VaultRecord>()
    private val capabilities = mutableSetOf<String>()
    private val bridgeModel = createBridgeModel()

    val bridge: BridgeModel
        get() = bridgeModel

    val protectedStringCount: Int
        get() = records.size

    fun protect(rawValue: String, callSiteIdentity: String): VaultReference? {
        val plaintext = CryptoPrimitives.utf8(rawValue)
        if (plaintext.isEmpty() || plaintext.size > MAX_PLAINTEXT_SIZE) {
            return null
        }

        val callSiteDigest =
            CryptoPrimitives.sha256(
                CryptoPrimitives.utf8(callSiteIdentity),
                byteArrayOf(0),
                plaintext,
            )
        val capability =
            CryptoPrimitives.hmacSha256(masterKey, CAPABILITY_LABEL, callSiteDigest)
                .copyOf(CAPABILITY_SIZE)
        check(capabilities.add(CryptoPrimitives.hex(capability))) {
            "Duplicate StrGuard call-site identity: $callSiteIdentity"
        }

        val gatewayIndex =
            (CryptoPrimitives.hmacSha256(masterKey, GATEWAY_LABEL, capability)[0].toInt() and 0xff) %
                    GATEWAY_COUNT
        val recordKey = deriveRecordKey(capability, gatewayIndex)
        val nonce =
            CryptoPrimitives.hmacSha256(recordKey, NONCE_LABEL, capability).copyOf(NONCE_SIZE)
        val associatedData = associatedData(capability, gatewayIndex, plaintext.size)
        val ciphertext =
            CryptoPrimitives.encryptChaCha20Poly1305(
                key = recordKey,
                nonce = nonce,
                associatedData = associatedData,
                plaintext = plaintext,
            )
        val paddingLength =
            (CryptoPrimitives.hmacSha256(masterKey, PADDING_LABEL, capability)[0].toInt() and 0x1f)
        val padding = deterministicBytes(PADDING_BYTES_LABEL, capability, paddingLength)
        val orderKey = CryptoPrimitives.hmacSha256(masterKey, ORDER_LABEL, capability)
        records +=
            VaultRecord(
                capability = capability,
                gatewayIndex = gatewayIndex,
                nonce = nonce,
                plaintextLength = plaintext.size,
                ciphertext = ciphertext,
                padding = padding,
                orderKey = orderKey,
            )

        plaintext.fill(0)
        recordKey.fill(0)
        return VaultReference(
            capabilityHigh = CryptoPrimitives.longFromBigEndian(capability, 0),
            capabilityLow = CryptoPrimitives.longFromBigEndian(capability, Long.SIZE_BYTES),
            gatewayIndex = gatewayIndex,
        )
    }

    fun writeNativeInputs(outputDirectory: Path): NativeVaultModel {
        outputDirectory.toFile().deleteRecursively()
        Files.createDirectories(outputDirectory)
        writeVault(outputDirectory.resolve(VAULT_FILE_NAME))
        val model =
            NativeVaultModel(
                bridge = bridgeModel,
                buildId = buildId.copyOf(),
                keyShares = encodeMasterKeyShares(),
            )
        writeRustConfig(outputDirectory.resolve(RUST_CONFIG_FILE_NAME), model)
        writeRuntimeMetadata(outputDirectory.resolve(RUNTIME_METADATA_FILE_NAME), model)
        return model
    }

    private fun deriveRecordKey(capability: ByteArray, gatewayIndex: Int): ByteArray =
        CryptoPrimitives.hkdfSha256(
            inputKeyMaterial = masterKey,
            salt = buildId,
            info = RECORD_KEY_LABEL + capability + byteArrayOf(gatewayIndex.toByte()),
            outputLength = KEY_SIZE,
        )

    private fun associatedData(
        capability: ByteArray,
        gatewayIndex: Int,
        plaintextLength: Int,
    ): ByteArray =
        VAULT_MAGIC +
                byteArrayOf(VAULT_VERSION.toByte()) +
                buildId +
                capability +
                byteArrayOf(gatewayIndex.toByte()) +
                CryptoPrimitives.intLe(plaintextLength)

    private fun writeVault(output: Path) {
        val orderedRecords = records.sortedWith { left, right -> compareBytes(left.orderKey, right.orderKey) }
        DataOutputStream(BufferedOutputStream(Files.newOutputStream(output))).use { data ->
            data.write(VAULT_MAGIC)
            data.writeByte(VAULT_VERSION)
            data.write(buildId)
            data.writeIntLe(orderedRecords.size)
            orderedRecords.forEach { record -> writeRecord(data, record) }
        }
    }

    private fun writeRecord(data: DataOutputStream, record: VaultRecord) {
        val bodyLength =
            CAPABILITY_SIZE +
                    1 +
                    NONCE_SIZE +
                    Int.SIZE_BYTES +
                    Int.SIZE_BYTES +
                    record.ciphertext.size +
                    Short.SIZE_BYTES +
                    record.padding.size
        data.writeIntLe(bodyLength)
        data.write(record.capability)
        data.writeByte(record.gatewayIndex)
        data.write(record.nonce)
        data.writeIntLe(record.plaintextLength)
        data.writeIntLe(record.ciphertext.size)
        data.write(record.ciphertext)
        data.writeShortLe(record.padding.size)
        data.write(record.padding)
    }

    private fun createBridgeModel(): BridgeModel {
        val classSuffix = deterministicHex(BRIDGE_CLASS_LABEL, 12)
        val librarySuffix = deterministicHex(LIBRARY_NAME_LABEL, 12)
        val methodNames =
            List(GATEWAY_COUNT) { index ->
                "m${deterministicHex(METHOD_NAME_LABEL + byteArrayOf(index.toByte()), 10)}"
            }
        val fileName = nativeTarget.packagedLibraryFileName(librarySuffix)
        return BridgeModel(
            internalClassName = "io/github/weg2022/strguard/generated/B$classSuffix",
            methodNames = methodNames,
            nativeLibraryResourcePath =
                "META-INF/strguard/native/${nativeTarget.resourceDirectory}/$fileName",
            nativeLibraryFileName = fileName,
        )
    }

    private fun encodeMasterKeyShares(): List<EncodedKeyShare> {
        val shares =
            MutableList(KEY_SHARE_COUNT - 1) { index ->
                deterministicBytes(KEY_SHARE_LABEL + byteArrayOf(index.toByte()), buildId, KEY_SIZE)
            }
        val finalShare = masterKey.copyOf()
        shares.forEach { share ->
            finalShare.indices.forEach { index -> finalShare[index] = (finalShare[index].toInt() xor share[index].toInt()).toByte() }
        }
        shares += finalShare
        return shares.mapIndexed { shareIndex, share ->
            val order = keyShareOrder(shareIndex)
            val mask =
                deterministicBytes(
                    KEY_SHARE_MASK_LABEL + byteArrayOf(shareIndex.toByte()),
                    buildId,
                    KEY_SIZE,
                )
            val encoded =
                ByteArray(KEY_SIZE) { encodedIndex ->
                    val sourceIndex = order[encodedIndex].toInt() and 0xff
                    (share[sourceIndex].toInt() xor mask[encodedIndex].toInt()).toByte()
                }
            share.fill(0)
            EncodedKeyShare(encoded, mask, order)
        }
    }

    private fun keyShareOrder(shareIndex: Int): ByteArray =
        (0 until KEY_SIZE)
            .map { sourceIndex ->
                sourceIndex to
                        deterministicBytes(
                            KEY_SHARE_ORDER_LABEL + byteArrayOf(shareIndex.toByte(), sourceIndex.toByte()),
                            buildId,
                            KEY_SIZE,
                        )
            }
            .sortedWith { left, right ->
                compareBytes(left.second, right.second).takeIf { it != 0 }
                    ?: left.first.compareTo(right.first)
            }
            .map { (sourceIndex) -> sourceIndex.toByte() }
            .toByteArray()

    private fun writeRustConfig(output: Path, model: NativeVaultModel) {
        val source = buildString {
            appendLine("pub const BRIDGE_CLASS: &str = \"${model.bridge.internalClassName}\";")
            appendLine("pub const METHOD_NAMES: [&str; $GATEWAY_COUNT] = [")
            model.bridge.methodNames.forEach { appendLine("    \"$it\",") }
            appendLine("];")
            appendLine("pub const BUILD_ID: [u8; $BUILD_ID_SIZE] = ${rustByteArray(model.buildId)};")
            appendLine("pub static ENCODED_KEY_SHARES: [[u8; $KEY_SIZE]; $KEY_SHARE_COUNT] = [")
            model.keyShares.forEach { appendLine("    ${rustByteArray(it.encoded)},") }
            appendLine("];")
            appendLine("pub static KEY_SHARE_MASKS: [[u8; $KEY_SIZE]; $KEY_SHARE_COUNT] = [")
            model.keyShares.forEach { appendLine("    ${rustByteArray(it.mask)},") }
            appendLine("];")
            appendLine("pub static KEY_SHARE_ORDERS: [[u8; $KEY_SIZE]; $KEY_SHARE_COUNT] = [")
            model.keyShares.forEach { appendLine("    ${rustByteArray(it.order)},") }
            appendLine("];")
        }
        Files.writeString(output, source)
    }

    private fun writeRuntimeMetadata(output: Path, model: NativeVaultModel) {
        Files.writeString(
            output,
            buildString {
                appendLine("resourcePath=${model.bridge.nativeLibraryResourcePath}")
                appendLine("fileName=${model.bridge.nativeLibraryFileName}")
            },
        )
    }

    private fun deterministicHex(label: ByteArray, byteCount: Int): String =
        CryptoPrimitives.hex(deterministicBytes(label, buildId, byteCount))

    private fun deterministicBytes(label: ByteArray, context: ByteArray, size: Int): ByteArray {
        if (size == 0) {
            return ByteArray(0)
        }
        val output = ByteArray(size)
        var written = 0
        var counter = 0
        while (written < size) {
            val block =
                CryptoPrimitives.hmacSha256(
                    masterKey,
                    label,
                    context,
                    CryptoPrimitives.intLe(counter),
                )
            val copyLength = minOf(block.size, size - written)
            block.copyInto(output, written, 0, copyLength)
            written += copyLength
            counter++
        }
        return output
    }

    private fun rustByteArray(bytes: ByteArray): String =
        bytes.joinToString(prefix = "[", postfix = "]") { byte -> "0x%02x".format(byte) }

    private fun compareBytes(left: ByteArray, right: ByteArray): Int {
        for (index in 0 until minOf(left.size, right.size)) {
            val comparison = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
            if (comparison != 0) {
                return comparison
            }
        }
        return left.size.compareTo(right.size)
    }

    private fun DataOutputStream.writeIntLe(value: Int) {
        write(ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
    }

    private fun DataOutputStream.writeShortLe(value: Int) {
        write(ByteBuffer.allocate(Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array())
    }
}

internal const val GATEWAY_COUNT = 8
internal const val VAULT_FILE_NAME = "vault.bin"
internal const val RUST_CONFIG_FILE_NAME = "native_config.rs"
internal const val RUNTIME_METADATA_FILE_NAME = "runtime.properties"
internal val VAULT_MAGIC = byteArrayOf('S'.code.toByte(), 'G'.code.toByte(), 'V'.code.toByte(), '2'.code.toByte())
internal const val VAULT_VERSION = 2
private const val BUILD_ID_SIZE = 16
private const val KEY_SIZE = 32
private const val KEY_SHARE_COUNT = 4
private const val CAPABILITY_SIZE = 16
private const val NONCE_SIZE = 12
private const val MAX_PLAINTEXT_SIZE = 60_000
private val BUILD_ID_LABEL = CryptoPrimitives.utf8("strguard/v2/build-id")
private val BUILD_KEY_LABEL = CryptoPrimitives.utf8("strguard/v2/build-key")
private val CAPABILITY_LABEL = CryptoPrimitives.utf8("strguard/v2/capability")
private val GATEWAY_LABEL = CryptoPrimitives.utf8("strguard/v2/gateway")
private val RECORD_KEY_LABEL = CryptoPrimitives.utf8("strguard/v2/record-key")
private val NONCE_LABEL = CryptoPrimitives.utf8("strguard/v2/nonce")
private val PADDING_LABEL = CryptoPrimitives.utf8("strguard/v2/padding-length")
private val PADDING_BYTES_LABEL = CryptoPrimitives.utf8("strguard/v2/padding-bytes")
private val ORDER_LABEL = CryptoPrimitives.utf8("strguard/v2/order")
private val BRIDGE_CLASS_LABEL = CryptoPrimitives.utf8("strguard/v2/bridge-class")
private val METHOD_NAME_LABEL = CryptoPrimitives.utf8("strguard/v2/method-name")
private val LIBRARY_NAME_LABEL = CryptoPrimitives.utf8("strguard/v2/library-name")
private val KEY_SHARE_LABEL = CryptoPrimitives.utf8("strguard/v2/key-share")
private val KEY_SHARE_MASK_LABEL = CryptoPrimitives.utf8("strguard/v2/key-share-mask")
private val KEY_SHARE_ORDER_LABEL = CryptoPrimitives.utf8("strguard/v2/key-share-order")
