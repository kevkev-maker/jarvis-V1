package com.example.myapp.jarvis

import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Offline listening with Vosk.
 *  - listenForWakeWord(): waits for "Jarvis" / "Hey Jarvis".
 *  - listenOnce(): hears one free sentence (backup when Google speech recognition fails).
 * Only one of these runs at a time, and stop() fully releases the microphone.
 * All callbacks arrive on the main thread.
 */
class VoskEars(private val model: Model, modelPath: String) {

    /**
     * true  = "exact" mode: Vosk only chooses between "jarvis", "hey jarvis" or unknown.
     * false = backup mode: Vosk transcribes everything and we look for the word "jarvis".
     */
    val exactMode: Boolean = VoskModelManager.vocabularyHasJarvis(modelPath) != false

    private var service: SpeechService? = null
    private var recognizer: Recognizer? = null
    private var generation = 0

    fun listenForWakeWord(onWake: () -> Unit, onError: (String) -> Unit) {
        stop()
        val myGen = generation
        val rec = try {
            if (exactMode) Recognizer(model, SAMPLE_RATE, WAKE_GRAMMAR) else Recognizer(model, SAMPLE_RATE)
        } catch (e: Exception) {
            onError("Could not start the wake-word recognizer: ${e.message}"); return
        }
        start(rec, null, object : RecognitionListener {
            override fun onPartialResult(hypothesis: String?) {}
            override fun onResult(hypothesis: String?) {
                if (myGen != generation) return
                val text = textOf(hypothesis)
                if (text.isNotEmpty()) Log.d(TAG, "Heard: '$text'")
                if (WAKE_REGEX.containsMatchIn(text)) onWake()
            }
            override fun onFinalResult(hypothesis: String?) {}
            override fun onError(exception: Exception?) {
                if (myGen == generation) onError("Microphone error: ${exception?.message}")
            }
            override fun onTimeout() {}
        }, onError)
    }

    /** Hears one sentence. [onText] gets "" if nothing was said before [timeoutMs]. */
    fun listenOnce(timeoutMs: Int, onText: (String) -> Unit) {
        stop()
        val myGen = generation
        var delivered = false
        fun deliver(t: String) {
            if (delivered || myGen != generation) return
            delivered = true
            onText(t)
        }
        val rec = try { Recognizer(model, SAMPLE_RATE) } catch (e: Exception) { deliver(""); return }
        start(rec, timeoutMs, object : RecognitionListener {
            override fun onPartialResult(hypothesis: String?) {}
            override fun onResult(hypothesis: String?) {
                val t = textOf(hypothesis)
                if (t.isNotEmpty()) deliver(t)
            }
            override fun onFinalResult(hypothesis: String?) { deliver(textOf(hypothesis)) }
            override fun onError(exception: Exception?) { deliver("") }
            override fun onTimeout() { deliver("") }
        }) { deliver("") }
    }

    /** Stops listening and releases the microphone. Safe to call many times. */
    fun stop() {
        generation++
        try { service?.stop() } catch (_: Exception) { }
        try { service?.shutdown() } catch (_: Exception) { }
        service = null
        try { recognizer?.close() } catch (_: Exception) { }
        recognizer = null
    }

    private fun start(
        rec: Recognizer,
        timeoutMs: Int?,
        listener: RecognitionListener,
        onError: (String) -> Unit
    ) {
        try {
            val s = SpeechService(rec, SAMPLE_RATE)
            recognizer = rec
            service = s
            if (timeoutMs != null) s.startListening(listener, timeoutMs) else s.startListening(listener)
        } catch (e: Exception) {
            try { rec.close() } catch (_: Exception) { }
            Log.e(TAG, "Mic start failed", e)
            onError("Microphone is busy or blocked (${e.message})")
        }
    }

    private fun textOf(json: String?): String =
        try { JSONObject(json ?: "{}").optString("text", "").trim() } catch (e: Exception) { "" }

    companion object {
        private const val TAG = "JARVIS_VOSK"
        private const val SAMPLE_RATE = 16000.0f
        private const val WAKE_GRAMMAR = "[\"hey jarvis\", \"jarvis\", \"[unk]\"]"
        private val WAKE_REGEX = Regex("\\bjarvis\\b")
    }
}
