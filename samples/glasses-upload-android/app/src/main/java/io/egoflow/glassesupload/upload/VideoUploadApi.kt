package io.egoflow.glassesupload.upload

import io.egoflow.glassesupload.data.UploadRecordEntity
import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody

class VideoUploadApi(
    private val client: OkHttpClient = OkHttpClient(),
) {
  fun upload(serverBaseUrl: String, file: File, record: UploadRecordEntity): Result<Unit> {
    val endpoint = serverBaseUrl.trimEnd('/') + "/api/videos"
    val metadataJson =
        """{"title":"${record.title.jsonEscape()}","discoveredAt":${record.discoveredAt},"sourcePath":"${record.sourcePath.jsonEscape()}"}"""
    val requestBody =
        MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("metadata", metadataJson)
            .addFormDataPart("file", file.name, file.asRequestBody("video/mp4".toMediaType()))
            .build()
    val request = Request.Builder().url(endpoint).post(requestBody).build()
    return runCatching {
      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
          error(response.body.string().ifBlank { "Upload failed with HTTP ${response.code}" })
        }
      }
    }
  }
}

private fun String.jsonEscape(): String = replace("\\", "\\\\").replace("\"", "\\\"")
