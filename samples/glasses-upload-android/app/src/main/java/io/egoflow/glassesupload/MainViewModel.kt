package io.egoflow.glassesupload

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.types.RegistrationState
import io.egoflow.glassesupload.data.AppDatabase
import io.egoflow.glassesupload.data.UploadRecordEntity
import io.egoflow.glassesupload.data.UploadRepository
import io.egoflow.glassesupload.settings.AppSettings
import io.egoflow.glassesupload.settings.SettingsRepository
import io.egoflow.glassesupload.upload.ImportDirectoryObserver
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConnectionCardState(
    val registrationLabel: String = "Permissions required",
    val isRegistered: Boolean = false,
    val hasActiveDevice: Boolean = false,
    val activeDeviceId: String? = null,
    val activeDeviceName: String? = null,
)

data class MainUiState(
    val permissionsGranted: Boolean = false,
    val connection: ConnectionCardState = ConnectionCardState(),
    val settings: AppSettings = AppSettings(),
    val watcherSummary: String = "Watcher idle",
    val history: List<UploadRecordEntity> = emptyList(),
    val canLoadMore: Boolean = false,
    val isLoadingHistory: Boolean = false,
    val message: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
  private val settingsRepository = SettingsRepository(application)
  private val uploadRepository =
      UploadRepository(
          context = application,
          database = AppDatabase.getInstance(application),
          settingsRepository = settingsRepository,
      )

  private val _uiState = MutableStateFlow(MainUiState())
  val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

  private val deviceSelector = AutoDeviceSelector()
  private var wearablesJob: Job? = null
  private var settingsJob: Job? = null
  private var observer: ImportDirectoryObserver? = null
  private var historyOffset = 0
  private val historyPageSize = 20

  fun onPermissionsGranted() {
    if (_uiState.value.permissionsGranted) {
      return
    }
    _uiState.update { it.copy(permissionsGranted = true, message = null) }
    startWearablesMonitoring()
    startSettingsMonitoring()
    refreshHistory(reset = true)
    viewModelScope.launch {
      uploadRepository.recoverPendingUploads()
    }
  }

  fun onPermissionsDenied() {
    _uiState.update { it.copy(message = "Bluetooth, camera, and video permissions are required.") }
  }

  fun startRegistration(activity: Activity) {
    Wearables.startRegistration(activity)
  }

  fun startUnregistration(activity: Activity) {
    Wearables.startUnregistration(activity)
  }

  fun updateServerBaseUrl(value: String) {
    viewModelScope.launch { settingsRepository.updateServerBaseUrl(value) }
  }

  fun updateImportDirectory(value: String) {
    viewModelScope.launch { settingsRepository.updateImportDirectory(value) }
  }

  fun updateDeleteAfterUpload(value: Boolean) {
    viewModelScope.launch { settingsRepository.updateDeleteAfterUpload(value) }
  }

  fun importCurrentDirectoryNow() {
    viewModelScope.launch {
      val count = uploadRepository.importCurrentDirectory()
      refreshHistory(reset = true)
      _uiState.update {
        it.copy(message = if (count == 0) "No new videos found in the import directory." else "Queued $count new video(s).")
      }
    }
  }

  fun retryUpload(recordId: Long) {
    viewModelScope.launch {
      uploadRepository.retry(recordId)
      refreshHistory(reset = true)
    }
  }

  fun loadMoreHistoryIfNeeded() {
    if (_uiState.value.isLoadingHistory || !_uiState.value.canLoadMore) {
      return
    }
    refreshHistory(reset = false)
  }

  fun clearMessage() {
    _uiState.update { it.copy(message = null) }
  }

  private fun startWearablesMonitoring() {
    wearablesJob?.cancel()
    wearablesJob =
        viewModelScope.launch {
          launch {
            Wearables.registrationState.collect { registrationState ->
              _uiState.update {
                it.copy(connection = it.connection.copy(registrationLabel = registrationState.toLabel(), isRegistered = registrationState is RegistrationState.Registered))
              }
            }
          }
          launch {
            deviceSelector.activeDevice(Wearables.devices).collect { deviceId ->
              val deviceName = deviceId?.let { activeDeviceId -> Wearables.devicesMetadata[activeDeviceId]?.value?.name }
              _uiState.update {
                it.copy(
                    connection =
                        it.connection.copy(
                            hasActiveDevice = deviceId != null,
                            activeDeviceId = deviceId,
                            activeDeviceName = deviceName,
                        ))
              }
            }
          }
        }
  }

  private fun startSettingsMonitoring() {
    settingsJob?.cancel()
    settingsJob =
        viewModelScope.launch {
          settingsRepository.settings.collect { settings ->
            _uiState.update { it.copy(settings = settings) }
            restartObserver(settings)
          }
        }
  }

  private fun restartObserver(settings: AppSettings) {
    observer?.close()
    val directory = settings.importDirectory.trim()
    if (directory.isEmpty()) {
      _uiState.update { it.copy(watcherSummary = "Set an import directory to start watching for videos.") }
      return
    }

    val target = File(directory)
    if (!target.exists() || !target.isDirectory) {
      _uiState.update { it.copy(watcherSummary = "Import directory does not exist: $directory") }
      return
    }

    observer =
        ImportDirectoryObserver(viewModelScope, target) { file ->
          viewModelScope.launch {
            uploadRepository.discoverNewVideo(file)
            refreshHistory(reset = true)
            _uiState.update { it.copy(watcherSummary = "Watching ${target.absolutePath}") }
          }
        }
    _uiState.update { it.copy(watcherSummary = "Watching ${target.absolutePath}") }
  }

  private fun refreshHistory(reset: Boolean) {
    viewModelScope.launch {
      if (reset) {
        historyOffset = 0
        _uiState.update { it.copy(isLoadingHistory = true) }
      } else {
        _uiState.update { it.copy(isLoadingHistory = true) }
      }
      val page = uploadRepository.loadHistoryPage(limit = historyPageSize, offset = historyOffset)
      val total = uploadRepository.totalHistoryCount()
      historyOffset += page.size
      _uiState.update {
        val merged = if (reset) page else it.history + page
        it.copy(history = merged, canLoadMore = merged.size < total, isLoadingHistory = false)
      }
    }
  }

  override fun onCleared() {
    observer?.close()
    super.onCleared()
  }
}

private fun RegistrationState.toLabel(): String =
    when (this) {
      is RegistrationState.Registered -> "Registered"
      is RegistrationState.Registering -> "Registering"
      is RegistrationState.Unregistered -> "Unregistered"
      is RegistrationState.Unregistering -> "Unregistering"
      is RegistrationState.Unavailable -> "Unavailable"
    }
