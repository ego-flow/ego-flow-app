/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 * Modified in this repository for EgoFlow; see THIRD_PARTY_NOTICES.md.
 */

import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
}

// Release signing is read from a gitignored keystore.properties (storeFile,
// storePassword, keyAlias, keyPassword) so real credentials stay out of version
// control. When the file is absent, a requested release build fails (see the
// task-graph guard below) rather than signing with a debug key. Debug builds use
// AGP's default debug keystore. See keystore.properties.example.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
  if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { load(it) }
  }
}
val hasReleaseSigning = keystoreProperties.getProperty("storeFile") != null

val localPropertiesFile = rootProject.file("local.properties")
val localProperties = Properties().apply {
  if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { load(it) }
  }
}

fun localOrEnvProperty(propertyName: String, envName: String, defaultValue: String = ""): String =
  System.getenv(envName) ?: localProperties.getProperty(propertyName) ?: defaultValue

val extentosProjectKey = localOrEnvProperty("extentos_project_key", "EXTENTOS_PROJECT_KEY")

// Refuse to build a release artifact without real signing credentials instead of
// silently signing it with a debug key. Guard on the task graph so debug builds
// (assembleDebug, unit tests) are unaffected; only a requested release
// assembly/bundle fails.
if (!hasReleaseSigning) {
  gradle.taskGraph.whenReady {
    val requiresReleaseSigning = allTasks.any { task ->
      task.project == project &&
        task.name.contains("Release") &&
        (task.name.startsWith("assemble") ||
          task.name.startsWith("bundle") ||
          task.name.startsWith("package"))
    }
    if (requiresReleaseSigning) {
      throw GradleException(
        "Release build requires keystore.properties with real signing credentials " +
          "(storeFile, storePassword, keyAlias, keyPassword). See keystore.properties.example. " +
          "Refusing to produce a release signed with a debug key.",
      )
    }
  }
}

android {
  namespace = "io.egoflow.app"
  compileSdk = 35

  buildFeatures { buildConfig = true }

  defaultConfig {
    applicationId = "io.egoflow.app"
    minSdk = 31
    targetSdk = 35
    versionCode = 4
    versionName = "0.0.1"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    vectorDrawables { useSupportLibrary = true }
    resourceConfigurations += listOf("en")
    manifestPlaceholders["mwdatApplicationId"] =
      localOrEnvProperty("mwdat_application_id", "MWDAT_APPLICATION_ID", "0")
    manifestPlaceholders["mwdatClientToken"] =
      localOrEnvProperty("mwdat_client_token", "MWDAT_CLIENT_TOKEN")
    buildConfigField("String", "EXTENTOS_SESSION_URL", "null")
    buildConfigField("String", "EXTENTOS_PROJECT_KEY", "\"$extentosProjectKey\"")
  }

  signingConfigs {
    // Debug builds use AGP's default auto-generated debug keystore
    // (~/.android/debug.keystore), so a fresh clone builds with no local setup.
    if (hasReleaseSigning) {
      create("release") {
        storeFile = file(keystoreProperties.getProperty("storeFile"))
        storePassword = keystoreProperties.getProperty("storePassword")
        keyAlias = keystoreProperties.getProperty("keyAlias")
        keyPassword = keystoreProperties.getProperty("keyPassword")
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Sign with the real release keystore when keystore.properties is present.
      // When absent, leave the release unsigned; the task-graph guard above fails
      // any release assembly/bundle before it can be built with the sample key.
      signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
  }
  kotlinOptions { jvmTarget = "1.8" }
  buildFeatures { compose = true }
  composeOptions { kotlinCompilerExtensionVersion = "1.5.1" }
  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
  implementation(project(":core"))
  implementation(project(":transport-rtmp"))
  implementation(project(":transport-whip"))
  implementation(project(":transport-http"))
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.exifinterface)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.material3)
  implementation(libs.compose.icons.lucide)
  implementation(libs.kotlinx.collections.immutable)
  implementation(libs.extentos.glasses)
  implementation(libs.extentos.glasses.ui)
  implementation(libs.mwdat.core)
  implementation(libs.mwdat.camera)
  // EgoFlow additions
  implementation(libs.okhttp)
  implementation(libs.camerax.core)
  implementation(libs.camerax.camera2)
  implementation(libs.camerax.lifecycle)
  implementation(libs.camerax.view)
  implementation(libs.datastore.preferences)
  implementation(libs.gson)
  implementation(libs.lifecycle.process)
  testImplementation("junit:junit:4.13.2")
  testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
  androidTestImplementation(libs.androidx.ui.test.junit4)
  androidTestImplementation(libs.androidx.test.uiautomator)
  androidTestImplementation(libs.androidx.test.rules)
}
