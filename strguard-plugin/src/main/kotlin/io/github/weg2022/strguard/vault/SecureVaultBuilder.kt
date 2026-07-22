package io.github.weg2022.strguard.vault

import io.github.weg2022.strguard.STRGUARD_ARTIFACT_MARKER_DIRECTORY
import io.github.weg2022.strguard.VaultRuntimeTarget
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
    private val runtimeTarget: VaultRuntimeTarget,
) : AutoCloseable {
    private val moduleBytes = CryptoPrimitives.utf8(moduleIdentity)
    private val targetBytes = CryptoPrimitives.utf8(runtimeTarget.vaultIdentity)
    private val buildId: ByteArray
    private val masterKey: ByteArray
    private val records = mutableListOf<VaultRecord>()
    private val capabilities = mutableSetOf<String>()
    private val bridgeModel: BridgeModel
    private var closed = false

    init {
        val releaseSeed = CryptoPrimitives.parseHex256(releaseSeedHex)
        try {
            buildId =
                CryptoPrimitives.hmacSha256(
                    releaseSeed,
                    BUILD_ID_LABEL,
                    moduleBytes,
                    byteArrayOf(0),
                    targetBytes,
                    inputDigest,
                ).clearedAfter { digest -> digest.copyOf(BUILD_ID_SIZE) }
            val keyInfo = BUILD_KEY_LABEL + moduleBytes + byteArrayOf(0) + targetBytes
            try {
                masterKey =
                    CryptoPrimitives.hkdfSha256(
                        inputKeyMaterial = releaseSeed,
                        salt = inputDigest,
                        info = keyInfo,
                        outputLength = KEY_SIZE,
                    )
            } finally {
                keyInfo.fill(0)
            }
        } finally {
            releaseSeed.fill(0)
        }
        bridgeModel = createBridgeModel()
    }

    val bridge: BridgeModel
        get() {
            checkOpen()
            return bridgeModel
        }

    val protectedStringCount: Int
        get() {
            checkOpen()
            return records.size
        }

    fun protect(rawValue: String, callSiteIdentity: String): VaultProtectionResult {
        checkOpen()
        if (rawValue.isEmpty()) return VaultProtectionResult.Empty
        if (rawValue.length > MAX_PLAINTEXT_CODE_UNITS) return VaultProtectionResult.TooLarge
        val plaintext = CryptoPrimitives.utf16Le(rawValue)
        val callSiteBytes = CryptoPrimitives.utf8(callSiteIdentity)
        var recordKey: ByteArray? = null
        try {
            val callSiteDigest =
                CryptoPrimitives.sha256(
                    callSiteBytes,
                    byteArrayOf(0),
                    plaintext,
                )
            val capability =
                CryptoPrimitives.hmacSha256(masterKey, CAPABILITY_LABEL, callSiteDigest)
                    .clearedAfter { digest -> digest.copyOf(CAPABILITY_SIZE) }
            callSiteDigest.fill(0)
            check(capabilities.add(CryptoPrimitives.hex(capability))) {
                "Duplicate StrGuard call-site identity: $callSiteIdentity"
            }

            val gatewayIndex =
                CryptoPrimitives.hmacSha256(masterKey, GATEWAY_LABEL, capability)
                    .clearedAfter { digest -> (digest[0].toInt() and 0xff) } %
                    GATEWAY_COUNT
            recordKey = deriveRecordKey(capability, gatewayIndex)
            val nonce =
                CryptoPrimitives.hmacSha256(recordKey, NONCE_LABEL, capability)
                    .clearedAfter { digest -> digest.copyOf(NONCE_SIZE) }
            val associatedData = associatedData(capability, gatewayIndex, rawValue.length)
            val ciphertext =
                CryptoPrimitives.encryptChaCha20Poly1305(
                    key = recordKey,
                    nonce = nonce,
                    associatedData = associatedData,
                    plaintext = plaintext,
                )
            associatedData.fill(0)
            val paddingLength =
                CryptoPrimitives.hmacSha256(masterKey, PADDING_LABEL, capability)
                    .clearedAfter { digest -> digest[0].toInt() and 0x1f }
            val padding = deterministicBytes(PADDING_BYTES_LABEL, capability, paddingLength)
            val orderKey = CryptoPrimitives.hmacSha256(masterKey, ORDER_LABEL, capability)
            records +=
                VaultRecord(
                    capability = capability,
                    gatewayIndex = gatewayIndex,
                    nonce = nonce,
                    plaintextLength = rawValue.length,
                    ciphertext = ciphertext,
                    padding = padding,
                    orderKey = orderKey,
                )

            return VaultProtectionResult.Protected(
                VaultReference(
                    capabilityHigh = CryptoPrimitives.longFromBigEndian(capability, 0),
                    capabilityLow = CryptoPrimitives.longFromBigEndian(capability, Long.SIZE_BYTES),
                    gatewayIndex = gatewayIndex,
                ),
            )
        } finally {
            callSiteBytes.fill(0)
            plaintext.fill(0)
            recordKey?.fill(0)
        }
    }

    fun writeNativeInputs(outputDirectory: Path): NativeVaultModel {
        checkOpen()
        outputDirectory.toFile().deleteRecursively()
        Files.createDirectories(outputDirectory)
        writeVault(outputDirectory.resolve(VAULT_FILE_NAME))
        val model =
            NativeVaultModel(
                bridge = bridgeModel,
                buildId = buildId.copyOf(),
                keyShares = encodeMasterKeyShares(),
            )
        try {
            writeRustConfig(outputDirectory.resolve(RUST_CONFIG_FILE_NAME), model)
            writeRuntimeMetadata(outputDirectory.resolve(RUNTIME_METADATA_FILE_NAME), model)
            return model
        } catch (failure: Throwable) {
            model.close()
            throw failure
        }
    }

    private fun deriveRecordKey(capability: ByteArray, gatewayIndex: Int): ByteArray {
        val info = RECORD_KEY_LABEL + capability + byteArrayOf(gatewayIndex.toByte())
        return try {
            CryptoPrimitives.hkdfSha256(
                inputKeyMaterial = masterKey,
                salt = buildId,
                info = info,
                outputLength = KEY_SIZE,
            )
        } finally {
            info.fill(0)
        }
    }

    private fun associatedData(
        capability: ByteArray,
        gatewayIndex: Int,
        plaintextLength: Int,
    ): ByteArray = VAULT_MAGIC +
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
        val loaderSuffix = deterministicHex(LOADER_CLASS_LABEL, 12)
        val librarySuffix = deterministicHex(LIBRARY_NAME_LABEL, 12)
        val methodNames =
            List(GATEWAY_COUNT) { index ->
                "m${deterministicHex(METHOD_NAME_LABEL + byteArrayOf(index.toByte()), 10)}"
            }
        val fileName = runtimeTarget.packagedLibraryFileName(librarySuffix)
        return BridgeModel(
            internalClassName = "io/github/weg2022/strguard/generated/B$classSuffix",
            loaderInternalClassName =
            if (runtimeTarget.runtimeFamily.extractFromResources) {
                "io/github/weg2022/strguard/generated/L$loaderSuffix"
            } else {
                null
            },
            methodNames = methodNames,
            nativeLibraryResourcePath = runtimeTarget.packagedResourcePath(fileName),
            nativeLibraryFileName = fileName,
            artifactMetadataResourcePath =
            "$STRGUARD_ARTIFACT_MARKER_DIRECTORY/${CryptoPrimitives.hex(buildId)}.properties",
            nativeLibraryLoadName = runtimeTarget.libraryLoadName(librarySuffix),
            extractFromResources = runtimeTarget.runtimeFamily.extractFromResources,
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

    private fun keyShareOrder(shareIndex: Int): ByteArray = (0 until KEY_SIZE)
        .map { sourceIndex ->
            sourceIndex to
                deterministicBytes(
                    KEY_SHARE_ORDER_LABEL + byteArrayOf(shareIndex.toByte(), sourceIndex.toByte()),
                    buildId,
                    KEY_SIZE,
                )
        }.let { rankedIndexes ->
            try {
                rankedIndexes.sortedWith { left, right ->
                    compareBytes(left.second, right.second).takeIf { it != 0 }
                        ?: left.first.compareTo(right.first)
                }.map { (sourceIndex) -> sourceIndex.toByte() }
                    .toByteArray()
            } finally {
                rankedIndexes.forEach { (_, rank) -> rank.fill(0) }
            }
        }

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
                appendLine("runtimeFamily=${runtimeTarget.runtimeFamily.name.lowercase()}")
                appendLine("artifactId=${CryptoPrimitives.hex(model.buildId)}")
                appendLine("bridgeClass=${model.bridge.internalClassName}")
                appendLine("loaderClass=${model.bridge.loaderInternalClassName.orEmpty()}")
                appendLine("gatewayNames=${model.bridge.methodNames.joinToString(",")}")
                appendLine("vaultRecords=$protectedStringCount")
                appendLine("resourcePath=${model.bridge.nativeLibraryResourcePath}")
                appendLine("fileName=${model.bridge.nativeLibraryFileName}")
            },
        )
    }

    private fun deterministicHex(label: ByteArray, byteCount: Int): String = deterministicBytes(label, buildId, byteCount).clearedAfter(CryptoPrimitives::hex)

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
            try {
                val copyLength = minOf(block.size, size - written)
                block.copyInto(output, written, 0, copyLength)
                written += copyLength
            } finally {
                block.fill(0)
            }
            counter++
        }
        return output
    }

    private fun rustByteArray(bytes: ByteArray): String = bytes.joinToString(prefix = "[", postfix = "]") { byte -> "0x%02x".format(byte) }

    private fun compareBytes(left: ByteArray, right: ByteArray): Int {
        for (index in 0 until minOf(left.size, right.size)) {
            val comparison = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
            if (comparison != 0) {
                return comparison
            }
        }
        return left.size.compareTo(right.size)
    }

    override fun close() {
        if (closed) return
        closed = true
        masterKey.fill(0)
        buildId.fill(0)
        moduleBytes.fill(0)
        targetBytes.fill(0)
        records.forEach { record ->
            record.capability.fill(0)
            record.nonce.fill(0)
            record.ciphertext.fill(0)
            record.padding.fill(0)
            record.orderKey.fill(0)
        }
        records.clear()
        capabilities.clear()
    }

    private fun checkOpen() {
        check(!closed) { "SecureVaultBuilder is closed" }
    }

    private fun DataOutputStream.writeIntLe(value: Int) {
        write(ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
    }

    private fun DataOutputStream.writeShortLe(value: Int) {
        write(ByteBuffer.allocate(Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array())
    }
}

private inline fun <T> ByteArray.clearedAfter(block: (ByteArray) -> T): T = try {
    block(this)
} finally {
    fill(0)
}

internal const val GATEWAY_COUNT = 8
internal const val VAULT_FILE_NAME = "vault.bin"
internal const val RUST_CONFIG_FILE_NAME = "native_config.rs"
internal const val RUNTIME_METADATA_FILE_NAME = "runtime.properties"
internal val VAULT_MAGIC = byteArrayOf('S'.code.toByte(), 'G'.code.toByte(), 'V'.code.toByte(), '3'.code.toByte())
internal const val VAULT_VERSION = 3
private const val BUILD_ID_SIZE = 16
private const val KEY_SIZE = 32
private const val KEY_SHARE_COUNT = 4
private const val CAPABILITY_SIZE = 16
private const val NONCE_SIZE = 12
private const val MAX_PLAINTEXT_CODE_UNITS = 30_000
private val BUILD_ID_LABEL = CryptoPrimitives.utf8("strguard/v3/build-id")
private val BUILD_KEY_LABEL = CryptoPrimitives.utf8("strguard/v3/build-key")
private val CAPABILITY_LABEL = CryptoPrimitives.utf8("strguard/v3/capability")
private val GATEWAY_LABEL = CryptoPrimitives.utf8("strguard/v3/gateway")
private val RECORD_KEY_LABEL = CryptoPrimitives.utf8("strguard/v3/record-key")
private val NONCE_LABEL = CryptoPrimitives.utf8("strguard/v3/nonce")
private val PADDING_LABEL = CryptoPrimitives.utf8("strguard/v3/padding-length")
private val PADDING_BYTES_LABEL = CryptoPrimitives.utf8("strguard/v3/padding-bytes")
private val ORDER_LABEL = CryptoPrimitives.utf8("strguard/v3/order")
private val BRIDGE_CLASS_LABEL = CryptoPrimitives.utf8("strguard/v3/bridge-class")
private val LOADER_CLASS_LABEL = CryptoPrimitives.utf8("strguard/v3/loader-class")
private val METHOD_NAME_LABEL = CryptoPrimitives.utf8("strguard/v3/method-name")
private val LIBRARY_NAME_LABEL = CryptoPrimitives.utf8("strguard/v3/library-name")
private val KEY_SHARE_LABEL = CryptoPrimitives.utf8("strguard/v3/key-share")
private val KEY_SHARE_MASK_LABEL = CryptoPrimitives.utf8("strguard/v3/key-share-mask")
private val KEY_SHARE_ORDER_LABEL = CryptoPrimitives.utf8("strguard/v3/key-share-order")
