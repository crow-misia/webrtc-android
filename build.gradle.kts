// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    kotlin("android") version Versions.kotlin apply false
}

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath(Deps.androidPlugin)
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
    delete(rootProject.buildDir)
}