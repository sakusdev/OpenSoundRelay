plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val previewVersionCode = providers.environmentVariable("OSR_VERSION_CODE")
    .orNull
    ?.toIntOrNull()
    ?: 4

android {
    namespace = "dev.sakus.osr"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "dev.sakus.osr"
        minSdk = 26
        targetSdk = 35
        versionCode = previewVersionCode
        versionName = "0.4.0-preview.$previewVersionCode"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}
