plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("android.extensions")
}

android {
    buildToolsVersion = "31.0.0"
    compileSdk = 31
    defaultConfig {
        applicationId = "org.appspot.apprtc"
        minSdk = 21
        targetSdk = 31
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Specifies the ABI configurations of your native
            // libraries Gradle should build and package with your APK.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    lint {
        textReport = true
        textOutput("stdout")
    }

    buildTypes {
        defaultConfig {
            multiDexEnabled = true
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    kotlinOptions {
        jvmTarget = "1.8"
        apiVersion = "1.5"
        languageVersion = "1.5"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation(Kotlin.stdlib)
    implementation(KotlinX.coroutines.core)
    implementation(KotlinX.coroutines.android)
    implementation(AndroidX.appCompat)
    implementation(AndroidX.preferenceKtx)
    implementation(AndroidX.core.ktx)
    implementation(Square.OkHttp3.okHttp)
    implementation(JakeWharton.timber)
    implementation("com.github.crow-misia:libwebrtc-bin:_")
    implementation("io.github.crow-misia.sdp:sdp:_")
    testImplementation(Testing.junit4)
    androidTestImplementation(Testing.junit4)
    androidTestImplementation(AndroidX.test.ext.junitKtx)
    androidTestImplementation(AndroidX.test.espresso.core)
}
