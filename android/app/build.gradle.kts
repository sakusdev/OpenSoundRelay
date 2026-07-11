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
        versionCode = 2
        versionName = "0.3.0-dev"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}
