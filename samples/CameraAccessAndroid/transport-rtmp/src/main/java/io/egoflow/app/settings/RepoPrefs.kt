package io.egoflow.app.settings

import android.content.Context
import android.content.SharedPreferences

object RepoPrefs {
    private const val PREFS_NAME = "egoflow_repo"
    private const val LEGACY_PREFS_NAME = "egoflow_settings"

    private const val KEY_REPOSITORY_ID = "egoFlowRepositoryId"
    private const val KEY_REPOSITORY_NAME = "egoFlowRepositoryName"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        migrateFromLegacyIfNeeded(context)
    }

    var egoFlowRepositoryId: String
        get() = prefs.getString(KEY_REPOSITORY_ID, null).orEmpty()
        set(value) = prefs.edit().putString(KEY_REPOSITORY_ID, value).apply()

    var egoFlowRepositoryName: String
        get() = prefs.getString(KEY_REPOSITORY_NAME, null).orEmpty()
        set(value) = prefs.edit().putString(KEY_REPOSITORY_NAME, value).apply()

    fun clear() {
        prefs.edit().clear().apply()
    }

    // One-time migration so users who already selected a repo before this split keep it.
    private fun migrateFromLegacyIfNeeded(context: Context) {
        if (prefs.contains(KEY_REPOSITORY_ID) || prefs.contains(KEY_REPOSITORY_NAME)) return
        val legacy = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val hasLegacy = legacy.contains(KEY_REPOSITORY_ID) || legacy.contains(KEY_REPOSITORY_NAME)
        if (!hasLegacy) return

        prefs.edit().apply {
            legacy.getString(KEY_REPOSITORY_ID, null)?.let { putString(KEY_REPOSITORY_ID, it) }
            legacy.getString(KEY_REPOSITORY_NAME, null)?.let { putString(KEY_REPOSITORY_NAME, it) }
        }.apply()

        legacy.edit()
            .remove(KEY_REPOSITORY_ID)
            .remove(KEY_REPOSITORY_NAME)
            .apply()
    }
}
