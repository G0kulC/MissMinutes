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
        versionCode = 3
        versionName = "2.1"
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
    // Offline wake word engine (no key needed). The model is downloaded on first start.
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    // The ghost's animations (assets/ghost, made by tools/ghost_lottie.py).
    implementation("com.airbnb.android:lottie:6.4.0")
    // Pico Camera: the camera preview in a small window (CameraX), on a plain ComponentActivity (no AppCompat).
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    testImplementation("junit:junit:4.13.2")
    // The real org.json for unit tests (Android's copy is only stubs off the phone).
    testImplementation("org.json:json:20231013")
}
