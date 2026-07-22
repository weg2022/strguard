package io.github.weg2022.strguard.vault

internal data class BridgeModel(
    val internalClassName: String,
    val loaderInternalClassName: String?,
    val methodNames: List<String>,
    val nativeLibraryResourcePath: String,
    val nativeLibraryFileName: String,
    val artifactMetadataResourcePath: String,
    val nativeLibraryLoadName: String,
    val extractFromResources: Boolean,
)

internal data class VaultReference(
    val capabilityHigh: Long,
    val capabilityLow: Long,
    val gatewayIndex: Int,
)

internal sealed interface VaultProtectionResult {
    data class Protected(val reference: VaultReference) : VaultProtectionResult

    data object Empty : VaultProtectionResult

    data object TooLarge : VaultProtectionResult
}

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
) : AutoCloseable {
    override fun close() {
        buildId.fill(0)
        keyShares.forEach(EncodedKeyShare::clear)
    }
}

internal class EncodedKeyShare(
    val encoded: ByteArray,
    val mask: ByteArray,
    val order: ByteArray,
) {
    fun clear() {
        encoded.fill(0)
        mask.fill(0)
        order.fill(0)
    }
}
