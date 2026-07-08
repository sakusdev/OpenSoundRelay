plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.sakus.osr"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.sakus.osr"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.0-dev"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}
