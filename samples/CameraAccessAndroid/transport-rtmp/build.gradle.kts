/*
 * :transport-rtmp — RTMP-over-TCP transport that talks to MediaMTX
 * (via ego-flow-server's ticket / heartbeat REST control plane).
 *
 * Phase 1 step 4: module skeleton only. RTMP source files migrate
 * in step 5; the wrapping `RtmpTransport : Transport` class lands
 * in step 7. Until then the existing RTMP code keeps living under
 * :app — :app simply gains a dependency on this empty module so
 * the project graph mirrors the eventual home of the bytes.
 */

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.jetbrains.kotlin.android)
}

android {
  namespace = "io.egoflow.app.transport.rtmp"
  compileSdk = 35

  defaultConfig {
    minSdk = 31
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
  }
  kotlinOptions { jvmTarget = "1.8" }

  testOptions {
    // Lets unit tests construct framework value classes like MediaCodec.BufferInfo
    // (used by RtmpSendQueueTest) without throwing "Stub!".
    unitTests.isReturnDefaultValues = true
  }
}

dependencies {
  implementation(project(":core"))
  // Wearables SDK supplies VideoFrame consumed by RtmpStreamer.
  implementation(libs.mwdat.camera)
  // EgoFlow REST control plane (login → ticket → heartbeat → stop) is
  // OkHttp + Gson. Coroutines are already exposed transitively via :core.
  implementation(libs.okhttp)
  implementation(libs.gson)

  testImplementation("junit:junit:4.13.2")
  // EgoFlowAuthClientTest and EgoFlowBackendClientHttpStreamTest spin a MockWebServer.
  testImplementation(libs.okhttp.mockwebserver)
}
