// ================================================
// build.gradle.kts  —  PROJECT ROOT
// ================================================
// JARVIS V1: plugin versions raised from 8.1.0 / 1.9.0 so the project builds
// with the Java 17/21 that ships inside current Android Studio.

plugins {
    id("com.android.application") version "8.5.2" apply false
    id("com.android.library") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
}
