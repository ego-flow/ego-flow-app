package io.egoflow.app.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

internal class AndroidKeystorePasswordCipher(
    private val keyAlias: String = KEY_ALIAS,
) {
    fun encrypt(password: String): String {
        return AesGcmCodec.encrypt(password, getOrCreateKey())
    }

    fun decrypt(payload: String): String {
        val key = loadKeyStore().getKey(keyAlias, null) as? SecretKey
            ?: error("Android Keystore password key is missing")
        return AesGcmCodec.decrypt(payload, key)
    }

    fun deleteKey() {
        loadKeyStore().deleteEntry(keyAlias)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = loadKeyStore()
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }

        // AndroidX Security 1.1 deprecates its crypto APIs in favor of direct
        // platform Keystore use. The key is non-exportable and restricted to
        // AES-GCM encryption/decryption with randomized IVs.
        // https://developer.android.com/privacy-and-security/keystore
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            keyAlias,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
            }
            .generateKey()
    }

    private fun loadKeyStore(): KeyStore {
        return KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "egoflow.auth.password.v1"
    }
}
