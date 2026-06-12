import java.util.Base64

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.harmonograph.jvywdn"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    val debugKeystore = file("${rootDir}/debug.keystore")
    if (!debugKeystore.exists()) {
      val baseFile = file("${rootDir}/debug.keystore.base64")
      if (baseFile.exists()) {
        try {
          val base64Text = baseFile.readText().replace("\\s".toRegex(), "")
          if (base64Text.isNotEmpty()) {
            val bytes = Base64.getDecoder().decode(base64Text)
            debugKeystore.writeBytes(bytes)
          }
        } catch (e: Exception) {
          println("Failed to decode base64 debug keystore: ${e.message}")
        }
      }
      if (!debugKeystore.exists()) {
        try {
          val pb = ProcessBuilder(
            "keytool", "-genkey", "-v",
            "-keystore", debugKeystore.absolutePath,
            "-storepass", "android",
            "-alias", "androiddebugkey",
            "-keypass", "android",
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "10000",
            "-dname", "CN=Android Debug,O=Android,C=US"
          )
          pb.inheritIO()
          val process = pb.start()
          process.waitFor()
        } catch (e: Exception) {
          println("Failed to generate debug keystore: ${e.message}")
        }
      }
    }

    create("debugConfig") {
      if (debugKeystore.exists()) {
        storeFile = debugKeystore
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
      } else {
        val defaultDebug = signingConfigs.getByName("debug")
        storeFile = defaultDebug.storeFile
        storePassword = defaultDebug.storePassword
        keyAlias = defaultDebug.keyAlias
        keyPassword = defaultDebug.keyPassword
      }
    }
    create("release") {
      val envKeystorePath = System.getenv("KEYSTORE_PATH")
      val keystorePath = envKeystorePath ?: "${rootDir}/my-upload-key.jks"
      val keystoreFile = file(keystorePath)
      
      val storePasswordVal = System.getenv("STORE_PASSWORD")
      val keyAliasVal = System.getenv("KEY_ALIAS") ?: "upload"
      val keyPasswordVal = System.getenv("KEY_PASSWORD")

      if (keystoreFile.exists() && !storePasswordVal.isNullOrEmpty() && !keyPasswordVal.isNullOrEmpty()) {
        storeFile = keystoreFile
        storePassword = storePasswordVal
        keyAlias = keyAliasVal
        keyPassword = keyPasswordVal
      } else {
        // Fallback to debug configuration to guarantee successful production builds
        if (debugKeystore.exists()) {
          storeFile = debugKeystore
          storePassword = "android"
          keyAlias = "androiddebugkey"
          keyPassword = "android"
        } else {
          val defaultDebug = signingConfigs.getByName("debug")
          storeFile = defaultDebug.storeFile
          storePassword = defaultDebug.storePassword
          keyAlias = defaultDebug.keyAlias
          keyPassword = defaultDebug.keyPassword
        }
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}
