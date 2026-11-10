// ================================================
// build.gradle.kts  —  MODULE app
// ================================================

import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// JARVIS V1: Firebase is optional. The old Alexandra login/history screens need it,
// JARVIS itself does not. If you later add your own app/google-services.json,
// Firebase switches back on automatically.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// Secrets live in local.properties (never committed to Git). Example line:
// GROQ_API_KEY=gsk_xxxxxxxx
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localProp(name: String, default: String = ""): String =
    (localProps.getProperty(name) ?: default).replace("\"", "")

android {
    namespace = "com.example.myapp"
    compileSdk = 34

    defaultConfig {
        // New app id so JARVIS installs next to (not over) any old Alexandra build.
        applicationId = "com.kevin.jarvis"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0-jarvis"

        buildConfigField("String", "GROQ_API_KEY", "\"${localProp("GROQ_API_KEY")}\"")
        buildConfigField("String", "GROQ_MODEL", "\"${localProp("GROQ_MODEL", "openai/gpt-oss-20b")}\"")
    }

    buildFeatures {
        buildConfig = true
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.viewpager2:viewpager2:1.0.0")

    // 🔥 Firebase BOM
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))

    // 🔐 Firebase Auth
    implementation("com.google.firebase:firebase-auth-ktx")

    // 🗄 Firestore
    implementation("com.google.firebase:firebase-firestore-ktx")

    // ✅ OkHttp pour appeler l'API Gemini
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ✅ Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // ✅ NOUVEAU : Glide pour photo profil circulaire
    implementation("com.github.bumptech.glide:glide:4.16.0")
    annotationProcessor("com.github.bumptech.glide:compiler:4.16.0")

    // JARVIS V1: Vosk offline speech recognition (Apache 2.0, free) for the wake word
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
