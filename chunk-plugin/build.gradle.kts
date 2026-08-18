// chunk-plugin: redistributable Android library for secure file chunking.
// Exposes ChunkProvider (chunk + SHA-256 + AES-256-GCM) and ChunkUploader
// (resumable OkHttp multipart upload). Published as an AAR via the android library plugin.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.chunkplugin"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okio:okio:3.17.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}