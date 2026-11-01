package com.example.myapp.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.myapp.AlexandraAccessibilityService
import com.example.myapp.IntroActivity
import com.google.android.material.button.MaterialButton
import com.google.firebase.FirebaseApp
import com.example.myapp.jarvis.JarvisUi as Ui

/** JARVIS home screen: setup steps, Start/Stop listening, voice settings. */
class JarvisActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var permText: TextView
    private lateinit var modelText: TextView
    private lateinit var modelProgress: ProgressBar
    private lateinit var modelButton: MaterialButton
    private lateinit var startButton: MaterialButton
    private var tester: JarvisSpeaker? = null
    private var downloading = false
    private lateinit var extraText: TextView
    private val statusListener: (String) -> Unit = { s -> runOnUiThread { showStatus(s) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        val root = Ui.column(this, 20)

        root.addView(Ui.title(this, "J.A.R.V.I.S."))
        root.addView(Ui.label(this, "Voice assistant · V1", 13f, Ui.MUTED))

        // Status
        val statusCard = Ui.card(this)
        statusText = Ui.label(this, "Off", 17f, Ui.ACCENT)
        statusCard.addView(Ui.label(this, "Status", 12f, Ui.MUTED))
        statusCard.addView(statusText)
        root.addView(Ui.spaced(this, statusCard, 20))

        startButton = Ui.button(this, "Start listening", primary = true) { toggleListening() }
        root.addView(Ui.spaced(this, startButton, 12))
        root.addView(Ui.spaced(this, Ui.label(this,
            "Say “Jarvis” or “Hey Jarvis”, pause, wait for the greeting, then speak your command. " +
                "While listening, a notification stays visible. Tap “Stop listening” there or here to turn the microphone off.",
            13f, Ui.MUTED), 8))

        // Setup
        root.addView(Ui.sectionHeader(this, "Setup"))

        val permCard = Ui.card(this)
        permCard.addView(Ui.label(this, "1. Permissions"))
        permText = Ui.label(this, "", 13f, Ui.MUTED)
        permCard.addView(permText)
        permCard.addView(Ui.spaced(this, Ui.button(this, "Allow permissions") { askPermissions() }))
        root.addView(permCard)

        val modelCard = Ui.card(this)
        modelCard.addView(Ui.label(this, "2. Offline wake-word model (one time, about 40 MB)"))
        modelText = Ui.label(this, "", 13f, Ui.MUTED)
        modelCard.addView(modelText)
        modelProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = ProgressBar.GONE
        }
        modelCard.addView(Ui.spaced(this, modelProgress, 8))
        modelButton = Ui.button(this, "Download model") { downloadModel() }
        modelCard.addView(Ui.spaced(this, modelButton))
        root.addView(Ui.spaced(this, modelCard, 12))

        val voiceCard = Ui.card(this)
        voiceCard.addView(Ui.label(this, "3. JARVIS's voice"))
        voiceCard.addView(Ui.label(this, "Listen to the voices on your phone and pick your favourite.", 13f, Ui.MUTED))
        voiceCard.addView(Ui.spaced(this, Ui.button(this, "Choose voice") {
            startActivity(Intent(this, VoiceSettingsActivity::class.java))
        }))
        voiceCard.addView(Ui.spaced(this, Ui.button(this, "Test greeting") { testGreeting() }, 8))
        root.addView(Ui.spaced(this, voiceCard, 12))

        val bgCard = Ui.card(this)
        bgCard.addView(Ui.label(this, "4. Keep JARVIS alive in the background"))
        bgCard.addView(Ui.label(this,
            "Some phones (Xiaomi, Oppo, Vivo, Realme, Samsung) stop background apps. " +
                "Set JARVIS's battery usage to “Unrestricted” / “No restrictions”.", 13f, Ui.MUTED))
        bgCard.addView(Ui.spaced(this, Ui.button(this, "Open battery settings for JARVIS") { openBatterySettings() }))
        root.addView(Ui.spaced(this, bgCard, 12))

        val uiCard = Ui.card(this)
        uiCard.addView(Ui.label(this, "5. Blue orb, top bar and WhatsApp sending"))
        uiCard.addView(Ui.label(this,
            "“Display over other apps” lets JARVIS show the top bar and the blue orb, and open WhatsApp for you. " +
                "Accessibility lets JARVIS press WhatsApp's Send button. It is used only for that.", 13f, Ui.MUTED))
        extraText = Ui.label(this, "", 13f, Ui.MUTED)
        uiCard.addView(Ui.spaced(this, extraText, 6))
        uiCard.addView(Ui.spaced(this, Ui.button(this, "Allow display over other apps") { openOverlaySettings() }))
        uiCard.addView(Ui.spaced(this, Ui.button(this, "Turn on JARVIS in Accessibility") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, 8))
        root.addView(Ui.spaced(this, uiCard, 12))

        // Extras
        root.addView(Ui.sectionHeader(this, "More"))
        val ai = if (JarvisBrain(this).aiEnabled) "on" else "off (optional, see README_JARVIS.md)"
        root.addView(Ui.label(this, "AI answers for open questions: $ai", 13f, Ui.MUTED))
        root.addView(Ui.spaced(this, Ui.button(this, "Open original Alexandra app") { openLegacyApp() }))
        root.addView(Ui.spaced(this, Ui.label(this,
            "Based on “Alexandra” by Leila-dev, MIT licence.", 12f, Ui.MUTED), 16))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        JarvisService.addStatusListener(statusListener)
        showStatus(JarvisService.lastStatus)
        refreshSetup()
        LocationHelper.refresh(this)   // saves a fresh location for "near me" questions
    }

    override fun onPause() {
        super.onPause()
        JarvisService.removeStatusListener(statusListener)
    }

    override fun onDestroy() {
        tester?.shutdown()
        super.onDestroy()
    }

    // ─── actions ─────────────────────────────────────────────────────────────

    private fun toggleListening() {
        if (JarvisService.isRunning) {
            JarvisService.stop(this)
            return
        }
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            Toast.makeText(this, "Please allow the microphone first (step 1).", Toast.LENGTH_LONG).show()
            askPermissions(); return
        }
        if (!VoskModelManager.isReady(this)) {
            Toast.makeText(this, "Please download the wake-word model first (step 2).", Toast.LENGTH_LONG).show()
            return
        }
        JarvisService.start(this)
        showStatus("Starting up…")
    }

    private fun showStatus(s: String) {
        statusText.text = s
        val running = JarvisService.isRunning
        startButton.text = if (running) "Stop listening" else "Start listening"
    }

    private fun neededPermissions(): List<String> {
        val list = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        return list
    }

    private fun askPermissions() {
        val missing = neededPermissions().filterNot { granted(it) }
        if (missing.isEmpty()) {
            Toast.makeText(this, "All permissions already allowed.", Toast.LENGTH_SHORT).show()
            return
        }
        ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshSetup()
        if (neededPermissions().any { !granted(it) }) {
            Toast.makeText(this,
                "If Android stopped asking, open Settings → Apps → JARVIS → Permissions.",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshSetup() {
        val names = mapOf(
            Manifest.permission.RECORD_AUDIO to "Microphone",
            Manifest.permission.READ_CONTACTS to "Contacts",
            Manifest.permission.CALL_PHONE to "Phone calls",
            Manifest.permission.ACCESS_FINE_LOCATION to "Precise location",
            Manifest.permission.ACCESS_COARSE_LOCATION to "Location",
            "android.permission.POST_NOTIFICATIONS" to "Notifications"
        )
        permText.text = neededPermissions().joinToString("\n") { p ->
            "${names[p] ?: p}: ${if (granted(p)) "allowed" else "not allowed"}"
        }
        extraText.text =
            "Display over other apps: ${if (Settings.canDrawOverlays(this)) "allowed" else "not allowed"}\n" +
                "Accessibility: ${if (AlexandraAccessibilityService.instance != null) "on" else "off"}"
        if (!downloading) {
            val ready = VoskModelManager.isReady(this)
            modelText.text = if (ready) "Ready. Works offline." else "Not downloaded yet. Use Wi-Fi if you can."
            modelButton.text = if (ready) "Download again" else "Download model"
        }
    }

    private fun downloadModel() {
        if (downloading) return
        if (JarvisService.isRunning) {
            Toast.makeText(this, "Stop listening first.", Toast.LENGTH_SHORT).show(); return
        }
        downloading = true
        modelButton.isEnabled = false
        modelProgress.visibility = ProgressBar.VISIBLE
        modelProgress.progress = 0
        modelText.text = "Downloading…"
        Thread {
            val error = try {
                VoskModelManager.download(applicationContext) { pct ->
                    runOnUiThread {
                        modelProgress.progress = pct
                        modelText.text = if (pct < 90) "Downloading… $pct%" else "Unpacking…"
                    }
                }
                null
            } catch (e: Exception) {
                e.message ?: "unknown error"
            }
            runOnUiThread {
                downloading = false
                modelButton.isEnabled = true
                modelProgress.visibility = ProgressBar.GONE
                refreshSetup()
                if (error != null) modelText.text = "Download failed: $error. Check internet and try again."
            }
        }.start()
    }

    private fun testGreeting() {
        val s = tester ?: JarvisSpeaker(this).also { tester = it }
        s.speak(JarvisPrefs.GREETING)
    }

    private fun openBatterySettings() {
        val intents = listOf(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        )
        for (i in intents) {
            try { startActivity(i); return } catch (_: Exception) { }
        }
    }

    private fun openOverlaySettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    private fun openLegacyApp() {
        // The old screens need Firebase. Without google-services.json they would crash.
        if (FirebaseApp.getApps(this).isEmpty()) {
            Toast.makeText(this,
                "The original app needs your own Firebase setup (google-services.json). Not needed for JARVIS.",
                Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent(this, IntroActivity::class.java))
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    companion object { private const val REQ_PERMS = 7 }
}
