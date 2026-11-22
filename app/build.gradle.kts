plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
// Samma version står i .github/workflows/build.yml
val appVersion = "0.6"
val keystoreFile = file("golfsvingen.p12")

android {
    namespace = "io.github.mrlundvik.golfsvingen"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.mrlundvik.golfsvingen"
        minSdk = 30
        targetSdk = 35
        versionCode = runNumber
        versionName = "$appVersion.$runNumber"
        // Bara 64-bitars ARM, som alla moderna telefoner. Halverar appens storlek
        ndk { abiFilters += "arm64-v8a" }
    }

    // MediaPipe läser kroppsmodellen direkt ur appen, och då får den inte vara komprimerad
    androidResources {
        noCompress += "task"
    }

    signingConfigs {
        create("golf") {
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storeType = "pkcs12"
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = "golfsvingen"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Samma nyckel varje gång, så att en ny version kan installeras över den gamla
            signingConfig = if (keystoreFile.exists()) signingConfigs.getByName("golf") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-transformer:1.5.1")
    implementation("androidx.annotation:annotation-experimental:1.4.1")
    implementation("com.google.mediapipe:tasks-vision:0.10.35")
}
