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

    /** First-launch questions answered. */
    var setupDone: Boolean
        get() = prefs.getBoolean("setup_done", false)
        set(value) = prefs.edit().putBoolean("setup_done", value).apply()

    /** Say "Call …?" and wait for yes before calling. */
    var confirmCalls: Boolean
        get() = prefs.getBoolean("confirm_calls", false)
        set(value) = prefs.edit().putBoolean("confirm_calls", value).apply()

    /** Read the message back and wait for yes before sending. */
    var confirmWhatsApp: Boolean
        get() = prefs.getBoolean("confirm_whatsapp", false)
        set(value) = prefs.edit().putBoolean("confirm_whatsapp", value).apply()

    /** Free Groq key typed in Setup (stays on this phone). */
    var groqKey: String
        get() = prefs.getString("groq_key", "") ?: ""
        set(value) = prefs.edit().putString("groq_key", value).apply()

    companion object {
        private const val KEY_VOICE = "voice_name"
        private const val KEY_RATE = "speech_rate"
        private const val KEY_PITCH = "pitch"

        const val GREETING = "Greetings, sir. At your service."
    }
}
