package com.example.myapp.jarvis

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

/**
 * JARVIS's voice. Wraps Android Text-to-Speech and applies the voice, speed and pitch
 * the user picked in Voice Settings. No voice is forced: until the user picks one,
 * the phone's default text-to-speech voice is used.
 */
class JarvisSpeaker(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = JarvisPrefs(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var tts: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private val readyWaiters = mutableListOf<(Boolean) -> Unit>()
    private val doneCallbacks = HashMap<String, () -> Unit>()
    private var counter = 0
    private var focusRequest: AudioFocusRequest? = null

    private val speechAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    init {
        tts = TextToSpeech(appContext) { status ->
            mainHandler.post {
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    tts?.setAudioAttributes(speechAttributes)
                    tts?.setOnUtteranceProgressListener(progressListener)
                    applySettings()
                } else {
                    failed = true
                    Log.e(TAG, "Text-to-speech failed to start (status $status)")
                }
                readyWaiters.forEach { it(ready) }
                readyWaiters.clear()
            }
        }
    }

    /** Runs [block] once the engine is ready (true) or has failed (false). */
    fun whenReady(block: (Boolean) -> Unit) {
        when {
            ready -> block(true)
            failed -> block(false)
            else -> readyWaiters.add(block)
        }
    }

    /** All voices the engine offers (may include voices that need downloading). */
    fun availableVoices(): List<Voice> =
        try { tts?.voices?.toList() ?: emptyList() } catch (e: Exception) { emptyList() }

    /** Re-reads saved voice, speed and pitch. Called before every sentence. */
    fun applySettings() {
        val engine = tts ?: return
        val wanted = prefs.voiceName
        var applied = false
        if (wanted.isNotEmpty()) {
            val voice = availableVoices().firstOrNull { it.name == wanted }
            if (voice != null) {
                applied = engine.setVoice(voice) == TextToSpeech.SUCCESS
            }
            if (!applied) Log.w(TAG, "Saved voice '$wanted' not available, using default")
        }
        if (!applied) {
            // No voice chosen yet: use the engine default, but make sure it speaks English.
            engine.defaultVoice?.let { engine.setVoice(it) }
            val lang = engine.voice?.locale?.language ?: ""
            if (lang != "en") engine.setLanguage(Locale.UK)
        }
        engine.setSpeechRate(prefs.speechRate)
        engine.setPitch(prefs.pitch)
    }

    /**
     * Speaks [text]. [onDone] runs on the main thread when speaking ends, fails,
     * or is interrupted — so the caller can always continue.
     */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        whenReady { ok ->
            if (!ok || text.isBlank()) {
                onDone?.let { mainHandler.post(it) }
                return@whenReady
            }
            applySettings()
            val id = "jarvis_${counter++}"
            if (onDone != null) doneCallbacks[id] = onDone
            requestFocus()
            val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
            if (result != TextToSpeech.SUCCESS) {
                finish(id)
            }
        }
    }

    /** Speak with temporary voice/speed/pitch (for "Play sample" before saving). */
    fun preview(text: String, voice: Voice?, rate: Float, pitch: Float) {
        whenReady { ok ->
            if (!ok) return@whenReady
            val engine = tts ?: return@whenReady
            val v = voice ?: engine.defaultVoice
            if (v != null) engine.setVoice(v)
            engine.setSpeechRate(rate)
            engine.setPitch(pitch)
            requestFocus()
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "preview_${counter++}")
        }
    }

    fun stop() {
        tts?.stop()
        val pending = doneCallbacks.keys.toList()
        pending.forEach { finish(it) }
    }

    fun shutdown() {
        doneCallbacks.clear()
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) { }
        tts = null
        abandonFocus()
    }

    // ─── internals ───────────────────────────────────────────────────────────

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) { finish(utteranceId) }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) { finish(utteranceId) }
        override fun onError(utteranceId: String?, errorCode: Int) { finish(utteranceId) }
        override fun onStop(utteranceId: String?, interrupted: Boolean) { finish(utteranceId) }
    }

    private fun finish(id: String?) {
        mainHandler.post {
            abandonFocus()
            val cb = if (id != null) doneCallbacks.remove(id) else null
            cb?.invoke()
        }
    }

    /** Lowers music volume while JARVIS talks. */
    private fun requestFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(speechAttributes)
            .build()
        focusRequest = req
        audioManager.requestAudioFocus(req)
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    companion object {
        private const val TAG = "JARVIS_TTS"
    }
}
