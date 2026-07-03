/*
 * :transport-whip — WHIP (WebRTC-HTTP Ingestion) transport that publishes to
 * MediaMTX over WebRTC.
 *
 * Mirrors :transport-rtmp: it implements the same `Transport` seam from :core
 * but ships media via a WebRTC PeerConnection instead of an RTMP socket. The
 * register → publish-ticket → close-intent control plane is identical to RTMP,
 * so this module depends on :transport-rtmp to reuse EgoFlowBackendClient /
 * AuthPrefs rather than duplicating the REST client. Only the publish step (SDP
 * offer/answer over HTTP + WebRTC media) is new here.
 */

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.jetbrains.kotlin.android)
}

android {
  namespace = "io.egoflow.app.transport.whip"
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
  // Reuse the EgoFlow REST control plane (login → register → publish-ticket →
  // close-intent) and the encrypted auth/repo prefs that already live in the RTMP
  // module; the WHIP flow shares those steps verbatim.
  implementation(project(":transport-rtmp"))
  // Wearables SDK supplies the VideoFrame consumed from the glasses path.
  implementation(libs.mwdat.camera)
  // WHIP signaling (POST SDP offer / DELETE session) is plain OkHttp.
  implementation(libs.okhttp)
  // libwebrtc: PeerConnection + I420 frame ingestion + H.264 encode.
  implementation(libs.webrtc.android)

  testImplementation("junit:junit:4.13.2")
  testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
