plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "io.github.dongyuwei.dsvpn"
  compileSdk = 36
  defaultConfig {
    applicationId = "io.github.dongyuwei.dsvpn"
    minSdk = 26
    targetSdk = 36
    // CI passes -PversionName/-PversionCode (e.g. from git tag and run number).
    versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
    versionName = (project.findProperty("versionName") as String?) ?: "1.0"
    // Optional per-device slimming: ./gradlew assembleDebug -Pabi=arm64-v8a
    // (comma-separated list). Without the property all four ABIs are packaged.
    ndk {
      (project.findProperty("abi") as String?)?.split(",")?.let { abiFilters.addAll(it) }
    }
    // The dsvpn C core (src/vpn.c, src/charm.c, src/os.c) is built in place
    // by CMake; see src/main/cpp/CMakeLists.txt.
    externalNativeBuild {
      cmake {
        arguments += "-DANDROID_STL=none"
      }
    }
    // Build stamp shown in the app footer: tag-version-hash-date.
    // CI passes -PgitTag/-PgitHash/-PbuildDate; local builds show only the version.
    fun prop(name: String) = (project.findProperty(name) as String?)?.trim().orEmpty()
    buildConfigField("String", "GIT_TAG", "\"${prop("gitTag")}\"")
    buildConfigField("String", "GIT_HASH", "\"${prop("gitHash")}\"")
    buildConfigField("String", "BUILD_DATE", "\"${prop("buildDate")}\"")
  }

  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "3.22.1"
    }
  }

  signingConfigs {
    // Release signing from environment (CI decodes the keystore from repo
    // secrets); falls back to the debug key so the APK is always installable.
    create("release") {
      val ksPath = System.getenv("DSVPN_KEYSTORE_PATH")
      val ksPass = System.getenv("DSVPN_KEYSTORE_PASSWORD")
      val ksAlias = System.getenv("DSVPN_KEY_ALIAS")
      if (ksPath != null && ksPass != null && ksAlias != null) {
        storeFile = file(ksPath)
        storePassword = ksPass
        keyAlias = ksAlias
        keyPassword = System.getenv("DSVPN_KEY_PASSWORD") ?: ksPass
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = if (System.getenv("DSVPN_KEYSTORE_PATH") != null) {
        signingConfigs.getByName("release")
      } else {
        signingConfigs.getByName("debug")
      }
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    aidl = false
    buildConfig = true
    shaders = false
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
}
