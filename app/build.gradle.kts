import java.util.Base64

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.meta.spatial.plugin)
  alias(libs.plugins.jetbrains.kotlin.plugin.compose)
}

/** The shared debug keystore is stored base64-encoded so the repository stays plain text. */
fun decodeB64(src: File, dst: File) {
  val bytes = Base64.getMimeDecoder().decode(src.readText().trim())
  if (!dst.exists() || !dst.readBytes().contentEquals(bytes)) {
    dst.parentFile.mkdirs()
    dst.writeBytes(bytes)
  }
}

val sharedDebugKeystore = layout.buildDirectory.file("debug-shared.keystore").get().asFile
decodeB64(file("debug-shared.keystore.b64"), sharedDebugKeystore)


android {
  namespace = "com.thehumanworks.wallbreach"
  compileSdk = 34

  defaultConfig {
    applicationId = "com.thehumanworks.wallbreach"
    // Quest 3 runs Horizon OS (Android 14 / API 34). minSdk 32 is Meta's store floor for Quest apps.
    minSdk = 32
    //noinspection OldTargetApi,ExpiredTargetSdkVersion
    targetSdk = 34
    versionCode = 1
    versionName = "1.0.0"
    ndkVersion = "27.0.12077973"
    ndk { abiFilters += "arm64-v8a" } // Quest is arm64 only
  }

  signingConfigs {
    // A debug keystore committed to the repo so every machine produces the same signature
    // (lets `adb install -r` upgrade in place no matter where the APK was built).
    getByName("debug") {
      storeFile = sharedDebugKeystore
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  packaging { resources.excludes.add("META-INF/LICENSE") }

  lint {
    abortOnError = false
    checkReleaseBuilds = false
  }

  buildTypes {
    debug { signingConfig = signingConfigs.getByName("debug") }
    release {
      isMinifyEnabled = false
      signingConfig = signingConfigs.getByName("debug")
    }
  }
  buildFeatures {
    buildConfig = true
    compose = true
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = "17" }
  testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
  implementation(libs.androidx.core.ktx)

  // Meta Spatial SDK
  implementation(libs.meta.spatial.sdk.base)
  implementation(libs.meta.spatial.sdk.toolkit)
  implementation(libs.meta.spatial.sdk.vr)
  implementation(libs.meta.spatial.sdk.compose)
  implementation(libs.meta.spatial.sdk.mruk)

  // Compose (in-world panels)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.material3)

  testImplementation(libs.junit)
}
