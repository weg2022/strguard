package io.github.weg2022.strguard.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object CryptoPrimitives {
    fun sha256(vararg values: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        values.forEach(digest::update)
        return digest.digest()
    }

    fun hmacSha256(key: ByteArray, vararg values: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        values.forEach(mac::update)
        return mac.doFinal()
    }

    fun hkdfSha256(
        inputKeyMaterial: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        outputLength: Int,
    ): ByteArray {
        require(outputLength in 1..(255 * 32)) { "Invalid HKDF output length: $outputLength" }
        val pseudoRandomKey = hmacSha256(salt, inputKeyMaterial)
        val output = ByteArray(outputLength)
        var generated = 0
        var previous = ByteArray(0)
        var counter = 1
        while (generated < outputLength) {
            previous = hmacSha256(pseudoRandomKey, previous, info, byteArrayOf(counter.toByte()))
            val copyLength = minOf(previous.size, outputLength - generated)
            previous.copyInto(output, generated, 0, copyLength)
            generated += copyLength
            counter++
        }
        pseudoRandomKey.fill(0)
        previous.fill(0)
        return output
    }

    fun encryptChaCha20Poly1305(
        key: ByteArray,
        nonce: ByteArray,
        associatedData: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        require(key.size == 32) { "ChaCha20-Poly1305 requires a 256-bit key" }
        require(nonce.size == 12) { "ChaCha20-Poly1305 requires a 96-bit nonce" }
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "ChaCha20"),
            IvParameterSpec(nonce),
        )
        cipher.updateAAD(associatedData)
        return cipher.doFinal(plaintext)
    }

    fun utf8(value: String): ByteArray = value.toByteArray(StandardCharsets.UTF_8)

    fun utf16Le(value: String): ByteArray = ByteArray(value.length * Char.SIZE_BYTES).also { output ->
        value.forEachIndexed { index, character ->
            val codeUnit = character.code
            output[index * Char.SIZE_BYTES] = codeUnit.toByte()
            output[index * Char.SIZE_BYTES + 1] = (codeUnit ushr Byte.SIZE_BITS).toByte()
        }
    }

    fun intLe(value: Int): ByteArray = ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    fun longFromBigEndian(value: ByteArray, offset: Int): Long = ByteBuffer.wrap(value, offset, Long.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).long

    fun hex(value: ByteArray): String = value.joinToString(separator = "") { byte -> "%02x".format(byte) }

    fun parseHex256(value: String): ByteArray {
        require(value.length == 64 && value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "strGuard.releaseSeedHex must contain exactly 64 hexadecimal characters"
        }
        return ByteArray(32) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }
}
