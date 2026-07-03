/*
 * :core — surface-agnostic foundations shared by :app and every
 * :transport-* module.
 *
 * Phase 1 of the dual-transport refactor: this module starts empty.
 * Source migrations (Wearables glue, PhoneCamera, StreamingService,
 * Transport interface, SettingsManager base, etc.) land in later
 * steps. Keeping it empty for the first build verifies the module
 * scaffolding before any code moves.
 */

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.jetbrains.kotlin.android)
}

android {
  namespace = "io.egoflow.app.core"
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
  // `api` so consumers (the :app module today, :transport-* modules
  // tomorrow) see StateFlow / Flow without an extra declaration.
  api(libs.kotlinx.coroutines.core)
  // Surface VideoFrame at the Transport boundary. Both RTMP and the
  // future slab-append transport consume raw glasses frames; keeping
  // the type here avoids each :transport-* module re-declaring its
  // own wrapper.
  api(libs.mwdat.camera)
}
