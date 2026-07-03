package io.egoflow.app.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Backend auth store. The backend password is a long-lived secret (the client
 * re-reads it to re-login on a 401), so it is persisted in an
 * [EncryptedSharedPreferences] file — AES256-GCM values keyed by a master key
 * held in the Android Keystore — rather than plaintext SharedPreferences.
 *
 * The encrypted file uses a distinct name ([PREFS_NAME]) because
 * EncryptedSharedPreferences cannot open a file previously written as plaintext.
 * A one-time migration lifts any values from the older plaintext stores
 * ([LEGACY_ENCRYPTED_NAME], [LEGACY_PREFS_NAME]) and then deletes them so no
 * cleartext password lingers on disk.
 */
object AuthPrefs {
    private const val TAG = "AuthPrefs"

    // Encrypted store (current).
    private const val PREFS_NAME = "egoflow_auth_secure"
    // Plaintext stores migrated away from, newest first.
    private const val LEGACY_ENCRYPTED_NAME = "egoflow_auth"
    private const val LEGACY_PREFS_NAME = "egoflow_settings"

    private const val KEY_API_BASE_URL = "egoFlowApiBaseUrl"
    private const val KEY_USER_ID = "egoFlowUserId"
    private const val KEY_PASSWORD = "egoFlowPassword"
    private const val KEY_REMEMBER_ME = "rememberMe"
    private const val KEY_DISPLAY_NAME = "authDisplayName"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = openEncryptedPrefs(context.applicationContext)
        migrateFromPlaintextIfNeeded(context.applicationContext)
    }

    var egoFlowApiBaseUrl: String
        get() = prefs.getString(KEY_API_BASE_URL, null).orEmpty()
        set(value) = prefs.edit().putString(KEY_API_BASE_URL, value).apply()

    var egoFlowUserId: String
        get() = prefs.getString(KEY_USER_ID, null).orEmpty()
        set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

    var egoFlowPassword: String
        get() = prefs.getString(KEY_PASSWORD, null).orEmpty()
        set(value) = prefs.edit().putString(KEY_PASSWORD, value).apply()

    var rememberMe: Boolean
        get() = prefs.getBoolean(KEY_REMEMBER_ME, false)
        set(value) = prefs.edit().putBoolean(KEY_REMEMBER_ME, value).apply()

    var authDisplayName: String
        get() = prefs.getString(KEY_DISPLAY_NAME, null).orEmpty()
        set(value) = prefs.edit().putString(KEY_DISPLAY_NAME, value).apply()

    fun hasEgoFlowBackendConfig(): Boolean {
        return egoFlowApiBaseUrl.isNotBlank() &&
            egoFlowUserId.isNotBlank() &&
            egoFlowPassword.isNotBlank()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    // Build the encrypted store. A corrupted keyset (e.g. partial data wipe, key
    // rotation) makes create() throw; recover once by deleting the encrypted file so
    // a fresh keyset is generated. The cost is a forced re-login, never a crash.
    private fun openEncryptedPrefs(context: Context): SharedPreferences {
        return try {
            buildEncryptedPrefs(context)
        } catch (error: Exception) {
            Log.w(TAG, "Encrypted auth store unreadable; resetting it", error)
            context.deleteSharedPreferences(PREFS_NAME)
            buildEncryptedPrefs(context)
        }
    }

    private fun buildEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey =
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // One-time lift of any previously-stored auth out of the plaintext files into the
    // encrypted store, so users who logged in before encryption don't get logged out.
    // The newer plaintext store ([LEGACY_ENCRYPTED_NAME]) wins over the oldest legacy one.
    private fun migrateFromPlaintextIfNeeded(context: Context) {
        if (prefs.contains(KEY_API_BASE_URL) || prefs.contains(KEY_USER_ID)) return

        // Apply oldest → newest so the most recent values take precedence.
        copyPlaintextInto(context, LEGACY_PREFS_NAME)
        copyPlaintextInto(context, LEGACY_ENCRYPTED_NAME)
    }

    private fun copyPlaintextInto(context: Context, plaintextName: String) {
        val source = context.getSharedPreferences(plaintextName, Context.MODE_PRIVATE)
        val hasAuth = source.contains(KEY_API_BASE_URL) ||
            source.contains(KEY_USER_ID) ||
            source.contains(KEY_PASSWORD) ||
            source.contains(KEY_REMEMBER_ME) ||
            source.contains(KEY_DISPLAY_NAME)
        if (!hasAuth) return

        prefs.edit().apply {
            source.getString(KEY_API_BASE_URL, null)?.let { putString(KEY_API_BASE_URL, it) }
            source.getString(KEY_USER_ID, null)?.let { putString(KEY_USER_ID, it) }
            source.getString(KEY_PASSWORD, null)?.let { putString(KEY_PASSWORD, it) }
            if (source.contains(KEY_REMEMBER_ME)) {
                putBoolean(KEY_REMEMBER_ME, source.getBoolean(KEY_REMEMBER_ME, false))
            }
            source.getString(KEY_DISPLAY_NAME, null)?.let { putString(KEY_DISPLAY_NAME, it) }
        }.apply()

        // Drop the plaintext copy so the cleartext password no longer sits at rest.
        source.edit().clear().apply()
        context.deleteSharedPreferences(plaintextName)
        Log.d(TAG, "Migrated auth from plaintext store '$plaintextName' into encrypted store")
    }
}
