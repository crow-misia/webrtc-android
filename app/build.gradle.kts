plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    buildToolsVersion = "33.0.2"
    compileSdk = 33
    defaultConfig {
        applicationId = "org.appspot.apprtc"
        namespace = "org.appspot.apprtc"
        minSdk = 21
        targetSdk = 33
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        viewBinding = true
    }

    lint {
        textReport = true
    }

    buildTypes {
        defaultConfig {
            multiDexEnabled = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    kotlinOptions {
        jvmTarget = "11"
        apiVersion = "1.8"
        languageVersion = "1.8"
    }

    compileOptions {
        sourceCompatibility(JavaVersion.VERSION_11)
        targetCompatibility(JavaVersion.VERSION_11)
    }
}

dependencies {
    implementation(KotlinX.coroutines.core)
    implementation(KotlinX.coroutines.android)
    implementation(AndroidX.archCore.runtime)
    implementation(AndroidX.appCompat)
    implementation(AndroidX.activity.ktx)
    implementation(AndroidX.core.ktx)
    implementation(AndroidX.fragment.ktx)
    implementation(AndroidX.preference.ktx)
    implementation(AndroidX.lifecycle.runtime.ktx)
    implementation(AndroidX.lifecycle.liveDataKtx)
    implementation(AndroidX.lifecycle.viewModelKtx)
    implementation(AndroidX.lifecycle.viewModelSavedState)
    implementation(AndroidX.window)
    implementation(Square.OkHttp3.okHttp)
    implementation(Square.okio)
    implementation(JakeWharton.timber)
    implementation(libs.libwebrtc.bin)
    implementation(libs.sdp)
    testImplementation(Testing.junit4)
    androidTestImplementation(Testing.junit4)
    androidTestImplementation(AndroidX.test.ext.junit.ktx)
    androidTestImplementation(AndroidX.test.espresso.core)
}
