package io.egoflow.glassesupload.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class AppSettings(
    val serverBaseUrl: String = "http://10.0.2.2:8000",
    val importDirectory: String = "/storage/emulated/0/Movies/EgoFlowImports",
    val deleteAfterUpload: Boolean = true,
)

class SettingsRepository(context: Context) {
  private val dataStore =
      PreferenceDataStoreFactory.create(
          produceFile = { context.preferencesDataStoreFile("egoflow-upload-settings.preferences_pb") })

  val settings: Flow<AppSettings> =
      dataStore.data.map { prefs ->
        AppSettings(
            serverBaseUrl = prefs[SERVER_BASE_URL] ?: AppSettings().serverBaseUrl,
            importDirectory = prefs[IMPORT_DIRECTORY] ?: AppSettings().importDirectory,
            deleteAfterUpload = prefs[DELETE_AFTER_UPLOAD] ?: AppSettings().deleteAfterUpload,
        )
      }

  suspend fun currentSettings(): AppSettings = settings.first()

  suspend fun updateServerBaseUrl(value: String) {
    dataStore.edit { it[SERVER_BASE_URL] = value }
  }

  suspend fun updateImportDirectory(value: String) {
    dataStore.edit { it[IMPORT_DIRECTORY] = value }
  }

  suspend fun updateDeleteAfterUpload(value: Boolean) {
    dataStore.edit { it[DELETE_AFTER_UPLOAD] = value }
  }

  private companion object {
    val SERVER_BASE_URL = stringPreferencesKey("server_base_url")
    val IMPORT_DIRECTORY = stringPreferencesKey("import_directory")
    val DELETE_AFTER_UPLOAD = booleanPreferencesKey("delete_after_upload")
  }
}
