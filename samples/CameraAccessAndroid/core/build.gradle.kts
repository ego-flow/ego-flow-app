/*
 * :core — surface-agnostic foundations shared by :app and every
 * :transport-* module.
 *
 * Owns the vendor-neutral Transport boundary, shared frame types,
 * MediaCodec wrapper, and YUV conversion utilities.
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
  testImplementation("junit:junit:4.13.2")
}
