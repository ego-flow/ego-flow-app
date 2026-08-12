package io.egoflow.app.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import io.egoflow.app.MainActivity
import io.egoflow.app.R
import io.egoflow.app.core.transport.api.TransportId

enum class StreamingSource {
  GLASSES,
  PHONE,
}

/**
 * Foreground service that keeps the camera streaming alive when the screen is locked
 * or the app is in the background.
 *
 * - Displays a persistent notification while streaming
 * - Acquires a partial wake lock to prevent CPU sleep
 * - Allows the streaming to continue when the app is backgrounded
 *
 * For the HTTP transport this also keeps the process alive through the final fMP4
 * chunk drain + /finish: stopStream() now calls [stop] only AFTER transport.stopSession()
 * (which does the drain) has returned, so the service outlives the live upload.
 */
class StreamingService : Service() {

  companion object {
    private const val TAG = "StreamingService"
    private const val CHANNEL_ID = "streaming_channel"
    private const val CHANNEL_NAME = "EgoFlow Streaming"
    private const val NOTIFICATION_ID = 1001
    private const val WAKELOCK_TAG = "EgoFlow::StreamingWakeLock"
    private const val ACTION_STOP =
        "io.egoflow.app.stream.ACTION_STOP"
    private const val EXTRA_SOURCE = "io.egoflow.app.stream.EXTRA_SOURCE"
    private const val EXTRA_TRANSPORT_MODE = "io.egoflow.app.stream.EXTRA_TRANSPORT_MODE"

    fun start(context: Context, source: StreamingSource, transportMode: TransportId) {
      val intent =
          Intent(context, StreamingService::class.java).apply {
            `package` = context.packageName
            putExtra(EXTRA_SOURCE, source.name)
            putExtra(EXTRA_TRANSPORT_MODE, transportMode.name)
          }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun stop(
        context: Context,
        source: StreamingSource,
        transportMode: TransportId,
    ) {
      // Route stop requests through onStartCommand instead of stopService so the service always
      // promotes to foreground before tearing itself down. Calling stopService while the service
      // is still in the post-startForegroundService grace window causes Android to throw
      // ForegroundServiceDidNotStartInTimeException and kill the process.
      val intent =
          Intent(context, StreamingService::class.java).apply {
            `package` = context.packageName
            action = ACTION_STOP
            putExtra(EXTRA_SOURCE, source.name)
            putExtra(EXTRA_TRANSPORT_MODE, transportMode.name)
          }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }
  }

  private var wakeLock: PowerManager.WakeLock? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    Log.d(TAG, "Service created")
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    Log.d(TAG, "Service started action=${intent?.action}")
    val source = sourceFromIntent(intent)
    val transportMode = transportModeFromIntent(intent)

    // Always promote to foreground first -- this is the single contract the platform enforces
    // within the post-startForegroundService grace window. Deferring this call for any reason
    // (including a pending stop) triggers ForegroundServiceDidNotStartInTimeException.
    val notification = createNotification(notificationText(source, transportMode, intent?.action))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(
          NOTIFICATION_ID,
          notification,
          foregroundServiceType(source, transportMode),
      )
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }

    if (intent?.action == ACTION_STOP) {
      releaseWakeLock()
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
      return START_NOT_STICKY
    }

    acquireWakeLock()
    return START_STICKY
  }

  override fun onDestroy() {
    Log.d(TAG, "Service destroyed")
    releaseWakeLock()
    super.onDestroy()
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel =
          NotificationChannel(
                  CHANNEL_ID,
                  CHANNEL_NAME,
                  NotificationManager.IMPORTANCE_LOW,
              )
              .apply {
                description = "Notifications for active camera streaming"
                setShowBadge(false)
              }

      val notificationManager = getSystemService(NotificationManager::class.java)
      notificationManager.createNotificationChannel(channel)
    }
  }

  private fun contentPendingIntent(): PendingIntent =
      PendingIntent.getActivity(
          this,
          0,
          Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
          },
          PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )

  private fun sourceFromIntent(intent: Intent?): StreamingSource =
      intent
          ?.getStringExtra(EXTRA_SOURCE)
          ?.let { value -> runCatching { StreamingSource.valueOf(value) }.getOrNull() }
          ?: StreamingSource.GLASSES

  private fun transportModeFromIntent(intent: Intent?): TransportId =
      intent
          ?.getStringExtra(EXTRA_TRANSPORT_MODE)
          ?.let { value -> runCatching { TransportId.valueOf(value) }.getOrNull() }
          ?: TransportId.RTMP

  private fun notificationText(
      source: StreamingSource,
      transportMode: TransportId,
      action: String?,
  ): String =
      when {
        action == ACTION_STOP && transportMode == TransportId.HTTP -> "Finishing upload..."
        action == ACTION_STOP -> "Finishing stream..."
        source == StreamingSource.PHONE -> "Streaming from your phone camera"
        else -> "Streaming from your glasses"
      }

  private fun foregroundServiceType(source: StreamingSource, transportMode: TransportId): Int {
    val captureType =
        when (source) {
          StreamingSource.GLASSES -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
          StreamingSource.PHONE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
    return if (transportMode == TransportId.HTTP) {
      captureType or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    } else {
      captureType
    }
  }

  private fun createNotification(text: String): Notification =
      NotificationCompat.Builder(this, CHANNEL_ID)
          .setContentTitle("EgoFlow streaming")
          .setContentText(text)
          .setSmallIcon(R.drawable.ic_stat_streaming)
          .setOngoing(true)
          .setOnlyAlertOnce(true)
          .setContentIntent(contentPendingIntent())
          .setPriority(NotificationCompat.PRIORITY_LOW)
          .setCategory(NotificationCompat.CATEGORY_SERVICE)
          .build()

  private fun acquireWakeLock() {
    if (wakeLock == null) {
      val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
      wakeLock =
          powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            acquire(10 * 60 * 1000L) // 10 minutes max
          }
      Log.d(TAG, "WakeLock acquired")
    }
  }

  private fun releaseWakeLock() {
    wakeLock?.let {
      if (it.isHeld) {
        it.release()
        Log.d(TAG, "WakeLock released")
      }
    }
    wakeLock = null
  }
}
