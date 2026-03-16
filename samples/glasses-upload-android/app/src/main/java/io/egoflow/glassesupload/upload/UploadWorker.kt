package io.egoflow.glassesupload.upload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import io.egoflow.glassesupload.R
import io.egoflow.glassesupload.data.AppDatabase
import io.egoflow.glassesupload.data.UploadRepository
import io.egoflow.glassesupload.settings.SettingsRepository
import java.io.File

class UploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
  private val repository =
      UploadRepository(
          context = appContext,
          database = AppDatabase.getInstance(appContext),
          settingsRepository = SettingsRepository(appContext),
      )
  private val uploadApi = VideoUploadApi()

  override suspend fun doWork(): Result {
    val recordId = inputData.getLong(KEY_RECORD_ID, -1L)
    if (recordId <= 0) {
      return Result.failure()
    }
    val record = repository.getRecord(recordId) ?: return Result.failure()
    val file = File(record.sourcePath)
    if (!file.exists()) {
      repository.markFailed(recordId, "Source file is missing.")
      return Result.failure()
    }

    val settings = repository.currentSettings()
    if (settings.serverBaseUrl.isBlank()) {
      repository.markFailed(recordId, "Server base URL is empty.")
      return Result.failure()
    }

    setForeground(foregroundInfo(record.title))
    repository.markUploading(recordId)

    val result = uploadApi.upload(settings.serverBaseUrl, file, record)
    return result.fold(
        onSuccess = {
          repository.markSucceeded(recordId)
          if (settings.deleteAfterUpload) {
            file.delete()
          }
          Result.success()
        },
        onFailure = { error ->
          repository.markFailed(recordId, error.message ?: "Unknown upload error.")
          Result.failure()
        },
    )
  }

  private fun foregroundInfo(title: String): ForegroundInfo {
    val manager =
        applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      manager.createNotificationChannel(
          NotificationChannel(CHANNEL_ID, "Video uploads", NotificationManager.IMPORTANCE_LOW))
    }
    val notification: Notification =
        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.upload_notification_title))
            .setContentText(title)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
    return ForegroundInfo(NOTIFICATION_ID, notification)
  }

  companion object {
    const val KEY_RECORD_ID = "record_id"
    private const val CHANNEL_ID = "uploads"
    private const val NOTIFICATION_ID = 1001
  }
}
