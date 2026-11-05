plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.personal.personalai.localllm"
    compileSdk = 36

    defaultConfig {
        minSdk = 36
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    packaging {
        jniLibs {
            // LiteRT-LM ships native .so files; prevent duplicate-file build errors
            pickFirsts += setOf(
                "**/liblitertlm.so",
                "**/libtensorflowlite_gpu_gl.so",
                "**/libtensorflowlite_gpu_delegate.so"
            )
        }
    }
}

dependencies {
    // On-device LLM inference (Google AI Edge)
    implementation(libs.litertlm.android)
    implementation(libs.play.services.tflite.gpu)

    // WorkManager for background model downloads
    implementation(libs.work.runtime.ktx)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // LiveData (for WorkManager progress observation in ModelDownloadManager)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
