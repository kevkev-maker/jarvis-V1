package com.example.myapp.jarvis

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Hears ONE spoken command after the greeting, using the phone's speech recognition
 * (usually Google). Better accuracy than the small offline model.
 * Must be used on the main thread.
 */
class CommandListener(private val context: Context) {

    sealed class Outcome {
        data class Heard(val text: String) : Outcome()
        object Silence : Outcome()          // user said nothing / not understood
        data class Failed(val reason: String) : Outcome()   // engine not usable → try Vosk backup
    }

    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var finished = true

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun listen(onOutcome: (Outcome) -> Unit) {
        cancel()
        if (!isAvailable) { onOutcome(Outcome.Failed("No speech recognition on this phone")); return }

        finished = false
        fun finish(o: Outcome) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            destroyRecognizer()
            onOutcome(o)
        }

        val r = try { SpeechRecognizer.createSpeechRecognizer(context) } catch (e: Exception) {
            finish(Outcome.Failed("Could not create recognizer: ${e.message}")); return
        }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                Log.d(TAG, "Command heard: '$text'")
                finish(if (text.isEmpty()) Outcome.Silence else Outcome.Heard(text))
            }

            override fun onError(error: Int) {
                Log.w(TAG, "Recognizer error $error")
                finish(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Outcome.Silence
                        else -> Outcome.Failed("speech error $error")
                    }
                )
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            finish(Outcome.Failed("start failed: ${e.message}")); return
        }
        // Safety net: some phones never call back. Give up after 12 seconds.
        handler.postDelayed({ finish(Outcome.Silence) }, 12_000)
    }

    fun cancel() {
        finished = true
        handler.removeCallbacksAndMessages(null)
        destroyRecognizer()
    }

    private fun destroyRecognizer() {
        try { recognizer?.cancel() } catch (_: Exception) { }
        try { recognizer?.destroy() } catch (_: Exception) { }
        recognizer = null
    }

    companion object { private const val TAG = "JARVIS_CMD" }
}
