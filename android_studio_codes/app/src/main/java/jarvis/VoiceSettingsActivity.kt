package com.example.myapp.jarvis

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale
import com.example.myapp.jarvis.JarvisUi as Ui

/**
 * Lets the user hear every voice the phone's Text-to-Speech offers and pick one,
 * plus speed and pitch. Nothing is locked in: the choice is saved and can be changed anytime.
 */
class VoiceSettingsActivity : AppCompatActivity() {

    private lateinit var prefs: JarvisPrefs
    private lateinit var speaker: JarvisSpeaker
    private lateinit var listBox: LinearLayout
    private lateinit var chosenText: TextView
    private lateinit var rateText: TextView
    private lateinit var pitchText: TextView
    private var englishOnly = true
    private var voices: List<Voice> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        prefs = JarvisPrefs(this)
        speaker = JarvisSpeaker(this)

        val root = Ui.column(this, 20)
        root.addView(Ui.title(this, "Voice"))
        root.addView(Ui.spaced(this, Ui.label(this,
            "Tap a voice to hear it and select it. Android doesn't label voices as male or female, so listen and choose. " +
                "For a classic JARVIS feel, try English (United Kingdom) voices with pitch a little below 1.0.",
            13f, Ui.MUTED), 6))

        chosenText = Ui.label(this, "", 14f, Ui.ACCENT)
        root.addView(Ui.spaced(this, chosenText, 14))

        // Speed
        rateText = Ui.label(this, "", 14f)
        root.addView(Ui.spaced(this, rateText, 16))
        root.addView(slider(prefs.speechRate) { v -> prefs.speechRate = v; updateLabels() })

        // Pitch
        pitchText = Ui.label(this, "", 14f)
        root.addView(Ui.spaced(this, pitchText, 12))
        root.addView(slider(prefs.pitch) { v -> prefs.pitch = v; updateLabels() })

        root.addView(Ui.spaced(this, Ui.button(this, "Play sample", primary = true) { playSample(null) }, 14))
        root.addView(Ui.spaced(this, Ui.button(this, "Use phone's default voice") {
            prefs.voiceName = ""; updateLabels(); renderList(); playSample(null)
        }, 8))
        root.addView(Ui.spaced(this, Ui.button(this, "Download more voices (Android settings)") { openTtsInstall() }, 8))

        root.addView(Ui.sectionHeader(this, "Voices on this phone"))
        root.addView(CheckBox(this).apply {
            text = "English voices only"
            setTextColor(Ui.TEXT)
            isChecked = englishOnly
            setOnCheckedChangeListener { _, checked -> englishOnly = checked; renderList() }
        })
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)
        listBox.addView(Ui.label(this, "Loading voices…", 13f, Ui.MUTED))

        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(root) })
        updateLabels()

        speaker.whenReady { ok ->
            if (!ok) {
                listBox.removeAllViews()
                listBox.addView(Ui.label(this,
                    "Text-to-speech isn't working on this phone. Install “Speech Services by Google” from the Play Store, then reopen this screen.",
                    14f, Ui.DANGER))
                return@whenReady
            }
            voices = speaker.availableVoices().sortedWith(compareBy({ localeRank(it.locale) }, { it.locale.toString() }, { it.name }))
            renderList()
        }
    }

    override fun onDestroy() {
        speaker.shutdown()
        super.onDestroy()
    }

    // ─── UI pieces ───────────────────────────────────────────────────────────

    /** SeekBar from 0.5 to 1.5 in steps of 0.05. */
    private fun slider(initial: Float, onChange: (Float) -> Unit) = SeekBar(this).apply {
        max = 20
        progress = ((initial - 0.5f) / 0.05f).toInt().coerceIn(0, 20)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) onChange(0.5f + p * 0.05f)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { playSample(null) }
        })
    }

    private fun updateLabels() {
        rateText.text = String.format(Locale.ENGLISH, "Speed: %.2f  (1.00 = normal)", prefs.speechRate)
        pitchText.text = String.format(Locale.ENGLISH, "Pitch: %.2f  (lower = deeper)", prefs.pitch)
        val name = prefs.voiceName
        chosenText.text = if (name.isEmpty()) "Selected: phone's default voice"
        else "Selected: " + (voices.firstOrNull { it.name == name }?.let { describe(it) } ?: name)
    }

    private fun renderList() {
        listBox.removeAllViews()
        val shown = voices.filter { !englishOnly || it.locale.language == "en" }
        if (shown.isEmpty()) {
            listBox.addView(Ui.label(this,
                "No voices found. Tap “Download more voices” and install English voices.", 14f, Ui.MUTED))
            return
        }
        for (v in shown) listBox.addView(row(v))
        updateLabels()
    }

    private fun row(v: Voice): TextView {
        val selected = v.name == prefs.voiceName
        val notes = mutableListOf<String>()
        if (v.isNetworkConnectionRequired) notes.add("needs internet")
        if (v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true) notes.add("not downloaded")
        val extra = if (notes.isEmpty()) "" else "  ·  " + notes.joinToString(", ")
        return Ui.label(this, describe(v) + extra + if (selected) "\nSelected" else "", 14f,
            if (selected) Ui.ACCENT else Ui.TEXT).apply {
            setPadding(Ui.dp(context, 14), Ui.dp(context, 12), Ui.dp(context, 14), Ui.dp(context, 12))
            background = GradientDrawable().apply {
                setColor(if (selected) Ui.CARD else Ui.BG)
                cornerRadius = Ui.dp(context, 10).toFloat()
                setStroke(Ui.dp(context, 1), if (selected) Ui.ACCENT else Ui.LINE)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(context, 8) }
            setOnClickListener {
                prefs.voiceName = v.name
                renderList()
                playSample(v)
                if (v.isNetworkConnectionRequired) {
                    Toast.makeText(this@VoiceSettingsActivity,
                        "This voice needs internet. JARVIS may go silent offline.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun describe(v: Voice): String = "${v.locale.displayName}\n${v.name}"

    private fun playSample(v: Voice?) {
        val voice = v ?: voices.firstOrNull { it.name == prefs.voiceName }
        speaker.preview(JarvisPrefs.GREETING, voice, prefs.speechRate, prefs.pitch)
    }

    private fun openTtsInstall() {
        try {
            startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
        } catch (e: Exception) {
            try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) } catch (_: Exception) {
                Toast.makeText(this, "Open Settings → Text-to-speech output on your phone.", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** British first (closest to the films), then US, Indian, other English, then the rest. */
    private fun localeRank(l: Locale): Int = when {
        l.language == "en" && l.country == "GB" -> 0
        l.language == "en" && l.country == "US" -> 1
        l.language == "en" && l.country == "IN" -> 2
        l.language == "en" -> 3
        else -> 4
    }
}
