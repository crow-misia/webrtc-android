plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("android.extensions")
}

android {
    buildToolsVersion(Versions.buildTools)
    compileSdkVersion(Versions.compileSdk)
    defaultConfig {
        applicationId = "org.appspot.apprtc"
        minSdkVersion(Versions.minSdk)
        targetSdkVersion(Versions.targetSdk)
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    lintOptions {
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

    ndkVersion = Versions.ndk

    kotlinOptions {
        jvmTarget = "1.8"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation(kotlin("stdlib-jdk7", Versions.kotlin))
    implementation(Deps.appcompat)
    implementation(Deps.preference)
    implementation(Deps.androidXcore)
    implementation(Deps.webrtc)
    implementation(Deps.okhttp3)
    implementation(Deps.timber)
    testImplementation(Deps.junit)
    androidTestImplementation(Deps.junit)
    androidTestImplementation(Deps.junitExt)
    androidTestImplementation(Deps.espresso)
}
