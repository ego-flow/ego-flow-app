/*
 * :transport-http — HTTP chunk-upload ingest transport (ingestType "HTTP").
 *
 * Mirrors :transport-whip structurally. Capture and upload OVERLAP: the encoder
 * output is muxed into append-only fMP4 segments (FragmentedMp4Muxer) and shipped
 * live via the /http-streams/{id}/{start,chunks,finish} routes during the session
 * (HttpLiveUploader), rather than recording a file and uploading it after stop.
 * Reuses EgoFlowBackendClient / AuthPrefs / IngestType from :transport-rtmp rather
 * than duplicating the REST client. No WebRTC peer connection is used; the
 * libwebrtc artifact only supplies its native I420 scaler for resolution normalization.
 * Network traffic goes through the reused EgoFlow REST client.
 */

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.jetbrains.kotlin.android)
}

android {
  namespace = "io.egoflow.app.transport.http"
  compileSdk = 35

  defaultConfig {
    minSdk = 31
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
  }
  kotlinOptions { jvmTarget = "1.8" }
}

dependencies {
  implementation(project(":core"))
  // Reuse the EgoFlow REST control plane (login → register → publish-ticket) plus
  // the new /http-streams REST methods, IngestType, and the encrypted auth/repo
  // prefs that already live in the RTMP module.
  implementation(project(":transport-rtmp"))
  // Wearables SDK supplies the VideoFrame consumed from the glasses path.
  implementation(libs.mwdat.camera)
  // Native libyuv I420 scaler used to normalize mid-session resolution changes.
  implementation(libs.webrtc.android)
  // Chunk upload is plain OkHttp.
  implementation(libs.okhttp)

  testImplementation("junit:junit:4.13.2")
  testImplementation(libs.okhttp.mockwebserver)
}
