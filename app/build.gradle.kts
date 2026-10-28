plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.missminutes.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.missminutes.agent"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Wake word engine. The .ppn file from Picovoice Console must match this major version (v3).
    implementation("ai.picovoice:porcupine-android:3.0.2")
}
