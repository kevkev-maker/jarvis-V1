package com.example.myapp.jarvis

import android.content.Context

/** Small saved settings for JARVIS (voice choice, speed, pitch). Stored only on the phone. */
class JarvisPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)

    /** Exact Android TTS voice name, or "" = not chosen yet (engine default is used). */
    var voiceName: String
        get() = prefs.getString(KEY_VOICE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_VOICE, value).apply()

    /** 1.0 = normal speed. */
    var speechRate: Float
        get() = prefs.getFloat(KEY_RATE, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_RATE, value).apply()

    /** 1.0 = normal pitch. Lower = deeper voice. */
    var pitch: Float
        get() = prefs.getFloat(KEY_PITCH, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_PITCH, value).apply()

    companion object {
        private const val KEY_VOICE = "voice_name"
        private const val KEY_RATE = "speech_rate"
        private const val KEY_PITCH = "pitch"

        const val GREETING = "Greetings, sir. At your service."
    }
}
