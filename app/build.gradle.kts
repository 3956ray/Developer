plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.thinkv2"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.thinkv2"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += "arm64-v8a" }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(files("libs/vosk-android-0.3.75.aar", "libs/jna-5.18.1.aar"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
// Explicit opt-in, synthetic isolated evaluation only; never included in the normal app APK.
if (providers.gradleProperty("senseVoiceEvaluation").orNull == "true") {
    android.sourceSets.getByName("androidTest") {
        kotlin.srcDir("src/senseVoiceEvaluation/kotlin")
        assets.srcDir("src/senseVoiceEvaluation/assets")
    }
    dependencies { androidTestImplementation(files("libs/evaluation/sherpa-onnx-1.13.8.aar")) }
}
