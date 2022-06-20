// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    kotlin("android") apply false
}

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath(Android.tools.build.gradlePlugin)
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
}

tasks.register("clean", Delete::class.java) {
    group = "build"
    delete(rootProject.buildDir)
}