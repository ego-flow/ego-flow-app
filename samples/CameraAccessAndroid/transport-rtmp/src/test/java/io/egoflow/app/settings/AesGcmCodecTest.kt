package io.egoflow.app.settings

import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AesGcmCodecTest {
    @Test
    fun `encrypted password round trips with the same key`() {
        val key = newAesKey()

        val encrypted = AesGcmCodec.encrypt("correct horse battery staple", key)

        assertEquals("correct horse battery staple", AesGcmCodec.decrypt(encrypted, key))
    }

    @Test
    fun `encrypting the same password twice uses different random ivs`() {
        val key = newAesKey()

        val first = AesGcmCodec.encrypt("same-password", key)
        val second = AesGcmCodec.encrypt("same-password", key)

        assertNotEquals(first, second)
        assertEquals("same-password", AesGcmCodec.decrypt(first, key))
        assertEquals("same-password", AesGcmCodec.decrypt(second, key))
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        val key = newAesKey()
        val encrypted = AesGcmCodec.encrypt("secret", key)
        val parts = encrypted.split(':').toMutableList()
        val firstCharacter = parts[2].first()
        parts[2] = (if (firstCharacter == 'A') 'B' else 'A') + parts[2].drop(1)

        assertThrows(GeneralSecurityException::class.java) {
            AesGcmCodec.decrypt(parts.joinToString(":"), key)
        }
    }

    @Test
    fun `unknown payload version is rejected`() {
        val key = newAesKey()
        val encrypted = AesGcmCodec.encrypt("secret", key)

        assertThrows(IllegalArgumentException::class.java) {
            AesGcmCodec.decrypt(encrypted.replaceBefore(':', "v2"), key)
        }
    }

    private fun newAesKey(): SecretKey {
        return KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
}
