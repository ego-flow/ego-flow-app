package io.egoflow.app.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Backend authentication state.
 *
 * Non-secret profile fields stay in app-private SharedPreferences. When the user opts into
 * remember-me, only the password is encrypted with an AES-GCM key held by Android Keystore.
 * Otherwise the password lives in process memory for the current session and never reaches disk.
 */
@SuppressLint("ApplySharedPref") // Synchronous commits below run only on Dispatchers.IO.
object AuthPrefs {
    private const val TAG = "AuthPrefs"

    private const val PREFS_NAME = "egoflow_auth_keystore"
    private const val LEGACY_SECURITY_CRYPTO_PREFS_NAME = "egoflow_auth_secure"
    private const val LEGACY_AUTH_PREFS_NAME = "egoflow_auth"
    private const val LEGACY_SETTINGS_PREFS_NAME = "egoflow_settings"

    private const val KEY_API_BASE_URL = "egoFlowApiBaseUrl"
    private const val KEY_USER_ID = "egoFlowUserId"
    private const val KEY_ENCRYPTED_PASSWORD = "egoFlowEncryptedPassword"
    private const val KEY_LEGACY_PASSWORD = "egoFlowPassword"
    private const val KEY_REMEMBER_ME = "rememberMe"
    private const val KEY_DISPLAY_NAME = "authDisplayName"

    private lateinit var prefs: SharedPreferences
    private lateinit var passwordCipher: AndroidKeystorePasswordCipher

    @Volatile private var passwordCache: String = ""

    suspend fun init(context: Context) {
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            passwordCipher = AndroidKeystorePasswordCipher()
            clearLegacyStores(appContext)
            passwordCache = loadRememberedPassword()
        }
    }

    val egoFlowApiBaseUrl: String
        get() = prefs.getString(KEY_API_BASE_URL, null).orEmpty()

    val egoFlowUserId: String
        get() = prefs.getString(KEY_USER_ID, null).orEmpty()

    val egoFlowPassword: String
        get() = passwordCache

    val rememberMe: Boolean
        get() = prefs.getBoolean(KEY_REMEMBER_ME, false)

    val authDisplayName: String
        get() = prefs.getString(KEY_DISPLAY_NAME, null).orEmpty()

    suspend fun saveLogin(
        apiBaseUrl: String,
        userId: String,
        password: String,
        rememberMe: Boolean,
        displayName: String,
    ) {
        withContext(Dispatchers.IO) {
            passwordCache = password
            val encryptedPassword =
                if (rememberMe && password.isNotEmpty()) {
                    encryptForPersistence(password)
                } else {
                    null
                }
            val persistPassword = encryptedPassword != null

            val editor =
                prefs.edit()
                    .putString(KEY_API_BASE_URL, apiBaseUrl)
                    .putString(KEY_USER_ID, userId)
                    .putBoolean(KEY_REMEMBER_ME, persistPassword)
                    .putString(KEY_DISPLAY_NAME, displayName)
            if (encryptedPassword == null) {
                editor.remove(KEY_ENCRYPTED_PASSWORD)
            } else {
                editor.putString(KEY_ENCRYPTED_PASSWORD, encryptedPassword)
            }
            if (!editor.commit()) {
                Log.w(TAG, "Failed to commit authentication preferences")
                prefs.edit()
                    .remove(KEY_ENCRYPTED_PASSWORD)
                    .putBoolean(KEY_REMEMBER_ME, false)
                    .commit()
            }
        }
    }

    fun clearLogin() {
        passwordCache = ""
        prefs.edit()
            .remove(KEY_ENCRYPTED_PASSWORD)
            .putBoolean(KEY_REMEMBER_ME, false)
            .putString(KEY_DISPLAY_NAME, "")
            .apply()
    }

    fun hasEgoFlowBackendConfig(): Boolean {
        return egoFlowApiBaseUrl.isNotBlank() &&
            egoFlowUserId.isNotBlank() &&
            egoFlowPassword.isNotBlank()
    }

    private fun loadRememberedPassword(): String {
        if (!prefs.getBoolean(KEY_REMEMBER_ME, false)) {
            prefs.edit().remove(KEY_ENCRYPTED_PASSWORD).commit()
            return ""
        }
        val payload = prefs.getString(KEY_ENCRYPTED_PASSWORD, null)
        if (payload.isNullOrBlank()) {
            disableRememberMe()
            return ""
        }

        return try {
            passwordCipher.decrypt(payload)
        } catch (error: Exception) {
            Log.w(TAG, "Stored password is unreadable; requiring login again", error)
            disableRememberMe(deleteKey = true)
            ""
        }
    }

    private fun encryptForPersistence(password: String): String? {
        return try {
            passwordCipher.encrypt(password)
        } catch (error: Exception) {
            Log.w(TAG, "Password could not be persisted securely; keeping it in memory", error)
            null
        }
    }

    private fun disableRememberMe(deleteKey: Boolean = false) {
        prefs.edit()
            .remove(KEY_ENCRYPTED_PASSWORD)
            .putBoolean(KEY_REMEMBER_ME, false)
            .commit()
        if (deleteKey) {
            runCatching { passwordCipher.deleteKey() }
                .onFailure { Log.w(TAG, "Failed to reset Android Keystore password key", it) }
        }
    }

    private fun clearLegacyStores(context: Context) {
        // This app has not shipped with the old format, so no credential migration is needed.
        // Delete the unreadable Security Crypto store and remove only auth keys from shared
        // legacy files; other app and repository settings in egoflow_settings must survive.
        context.deleteSharedPreferences(LEGACY_SECURITY_CRYPTO_PREFS_NAME)
        clearLegacyAuthKeys(context, LEGACY_AUTH_PREFS_NAME)
        clearLegacyAuthKeys(context, LEGACY_SETTINGS_PREFS_NAME)
    }

    private fun clearLegacyAuthKeys(context: Context, prefsName: String) {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_API_BASE_URL)
            .remove(KEY_USER_ID)
            .remove(KEY_LEGACY_PASSWORD)
            .remove(KEY_REMEMBER_ME)
            .remove(KEY_DISPLAY_NAME)
            .commit()
    }
}
