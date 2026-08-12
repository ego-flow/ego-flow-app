package io.egoflow.app.settings

import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object AesGcmCodec {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PAYLOAD_VERSION = "v1"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val GCM_IV_LENGTH_BYTES = 12

    fun encrypt(plaintext: String, key: SecretKey): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return listOf(
                PAYLOAD_VERSION,
                encoder.encodeToString(cipher.iv),
                encoder.encodeToString(ciphertext),
            )
            .joinToString(":")
    }

    fun decrypt(payload: String, key: SecretKey): String {
        val parts = payload.split(':')
        require(parts.size == 3 && parts[0] == PAYLOAD_VERSION) {
            "Unsupported encrypted password payload"
        }

        val decoder = Base64.getUrlDecoder()
        val iv = decoder.decode(parts[1])
        require(iv.size == GCM_IV_LENGTH_BYTES) { "Invalid AES-GCM IV" }
        val ciphertext = decoder.decode(parts[2])

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }
}
