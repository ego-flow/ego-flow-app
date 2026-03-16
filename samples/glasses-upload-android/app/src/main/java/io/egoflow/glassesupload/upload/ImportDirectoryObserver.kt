package io.egoflow.glassesupload.upload

import android.os.FileObserver
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ImportDirectoryObserver(
    private val scope: CoroutineScope,
    private val directory: File,
    private val onVideoReady: suspend (File) -> Unit,
) : Closeable {
  private val knownPaths = directory.listFiles().orEmpty().map { it.absolutePath }.toMutableSet()

  private val observer =
      object : FileObserver(directory, CLOSE_WRITE or MOVED_TO or CREATE) {
        override fun onEvent(event: Int, path: String?) {
          if (path.isNullOrBlank()) {
            return
          }
          val candidate = File(directory, path)
          if (candidate.absolutePath in knownPaths) {
            return
          }
          knownPaths += candidate.absolutePath
          scope.launch {
            delay(750)
            onVideoReady(candidate)
          }
        }
      }

  init {
    observer.startWatching()
  }

  override fun close() {
    observer.stopWatching()
  }
}
