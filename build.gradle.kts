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
        classpath("com.android.tools.build:gradle:_")
    }
}

tasks.register("clean", Delete::class.java) {
    group = "build"
    delete(rootProject.buildDir)
}