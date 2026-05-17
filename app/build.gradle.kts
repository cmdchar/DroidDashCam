plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.helge.droiddashcam"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.helge.droiddashcam"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    buildFeatures {
        viewBinding = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // CameraX dependencies - 1.4.0 is more stable in this env
    val cameraxVersion = "1.4.0"
    implementation("androidx.camera:camera-core:${cameraxVersion}")
    implementation("androidx.camera:camera-camera2:${cameraxVersion}")
    implementation("androidx.camera:camera-lifecycle:${cameraxVersion}")
    implementation("androidx.camera:camera-video:${cameraxVersion}")
    implementation("androidx.camera:camera-view:${cameraxVersion}")
    implementation("androidx.camera:camera-extensions:${cameraxVersion}")

    // RTMP Streaming dependencies
    val rootEncoderVersion = "2.4.5"
    implementation("com.github.pedroSG94.RootEncoder:library:${rootEncoderVersion}")

    // ExoPlayer for remote viewing
    val media3Version = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:${media3Version}")
    implementation("androidx.media3:media3-exoplayer-rtsp:${media3Version}")
    implementation("androidx.media3:media3-datasource-rtmp:${media3Version}")
    implementation("androidx.media3:media3-ui:${media3Version}")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
