package io.github.weg2022.strguard.vault

internal data class BridgeModel(
    val internalClassName: String,
    val methodNames: List<String>,
    val nativeLibraryResourcePath: String,
    val nativeLibraryFileName: String,
)

internal data class VaultReference(
    val capabilityHigh: Long,
    val capabilityLow: Long,
    val gatewayIndex: Int,
)

internal class VaultRecord(
    val capability: ByteArray,
    val gatewayIndex: Int,
    val nonce: ByteArray,
    val plaintextLength: Int,
    val ciphertext: ByteArray,
    val padding: ByteArray,
    val orderKey: ByteArray,
)

internal class NativeVaultModel(
    val bridge: BridgeModel,
    val buildId: ByteArray,
    val keyShares: List<EncodedKeyShare>,
)

internal class EncodedKeyShare(
    val encoded: ByteArray,
    val mask: ByteArray,
    val order: ByteArray,
)
