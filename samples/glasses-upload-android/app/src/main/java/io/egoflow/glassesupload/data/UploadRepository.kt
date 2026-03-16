package io.egoflow.glassesupload.data

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.egoflow.glassesupload.settings.SettingsRepository
import io.egoflow.glassesupload.upload.UploadWorker
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UploadRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val settingsRepository: SettingsRepository,
) {
  private val dao = database.uploadRecordDao()
  private val workManager = WorkManager.getInstance(context)

  suspend fun discoverNewVideo(file: File): Long? {
    if (!file.exists() || !file.isFile || !file.isVideoFile()) {
      return null
    }
    val record =
        UploadRecordEntity(
            sourcePath = file.absolutePath,
            title = file.nameWithoutExtension,
            durationMs = readDuration(file),
            discoveredAt = System.currentTimeMillis(),
            status = UploadStatus.WAITING,
            errorMessage = null,
            uploadedAt = null,
        )
    val insertedId = dao.insert(record)
    if (insertedId == -1L) {
      return null
    }
    enqueueUpload(insertedId)
    return insertedId
  }

  suspend fun importCurrentDirectory(): Int {
    val directory = settingsRepository.currentSettings().importDirectory.trim()
    if (directory.isEmpty()) {
      return 0
    }
    return withContext(Dispatchers.IO) {
      var importedCount = 0
      File(directory)
          .takeIf { it.exists() && it.isDirectory }
          ?.listFiles()
          .orEmpty()
          .filter { it.isFile && it.isVideoFile() }
          .forEach { file ->
            if (discoverNewVideo(file) != null) {
              importedCount += 1
            }
          }
      importedCount
    }
  }

  suspend fun retry(recordId: Long) {
    dao.updateStatus(recordId, UploadStatus.WAITING, null, null)
    enqueueUpload(recordId)
  }

  suspend fun recoverPendingUploads() {
    dao.loadByStatuses(listOf(UploadStatus.WAITING.name, UploadStatus.UPLOADING.name)).forEach { record ->
      enqueueUpload(record.id)
    }
  }

  suspend fun loadHistoryPage(limit: Int, offset: Int): List<UploadRecordEntity> = dao.historyPage(limit, offset)

  suspend fun totalHistoryCount(): Int = dao.totalCount()

  suspend fun getRecord(recordId: Long): UploadRecordEntity? = dao.getById(recordId)

  suspend fun markUploading(recordId: Long) {
    dao.updateStatus(recordId, UploadStatus.UPLOADING, null, null)
  }

  suspend fun markSucceeded(recordId: Long) {
    dao.updateStatus(recordId, UploadStatus.SUCCEEDED, null, System.currentTimeMillis())
  }

  suspend fun markFailed(recordId: Long, message: String) {
    dao.updateStatus(recordId, UploadStatus.FAILED, message, null)
  }

  suspend fun currentSettings() = settingsRepository.currentSettings()

  private fun enqueueUpload(recordId: Long) {
    val request =
        OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf(UploadWorker.KEY_RECORD_ID to recordId))
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
    workManager.enqueueUniqueWork("upload-record-$recordId", ExistingWorkPolicy.REPLACE, request)
  }

  private suspend fun readDuration(file: File): Long? =
      withContext(Dispatchers.IO) {
        runCatching {
              val retriever = MediaMetadataRetriever()
              try {
                retriever.setDataSource(file.absolutePath)
                retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
              } finally {
                retriever.release()
              }
            }
            .getOrNull()
      }
}

private fun File.isVideoFile(): Boolean {
  val name = name.lowercase()
  return name.endsWith(".mp4") || name.endsWith(".mov") || name.endsWith(".m4v")
}
