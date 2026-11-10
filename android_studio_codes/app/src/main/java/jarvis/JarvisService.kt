package com.example.myapp.jarvis

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.myapp.Contact
import com.example.myapp.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vosk.Model
import java.util.concurrent.CopyOnWriteArraySet

/**
 * JARVIS's listening loop, running as a foreground service so it keeps working
 * while you use other apps. Android requires the visible notification; its
 * "Stop listening" button turns the microphone off completely.
 *
 * Loop:  wait for "Jarvis" (offline, Vosk) → show orb / top bar → greeting
 *        → hear one command → answer / act → back to waiting.
 */
class JarvisService : Service() {

    private class PendingChoice(val options: List<Contact>, val onPick: (Contact) -> JarvisBrain.Reply)

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var speaker: JarvisSpeaker
    private lateinit var brain: JarvisBrain
    private lateinit var commandListener: CommandListener
    private var model: Model? = null
    private var ears: VoskEars? = null

    private var alive = false
    private var inSession = false
    private var useVoskForCommands = false
    private var pending: PendingChoice? = null
    private var pendingConfirm: (() -> JarvisBrain.Reply)? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        brain = JarvisBrain(this)
        commandListener = CommandListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (!alive) {
            if (!goForeground()) return START_NOT_STICKY
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            speaker = JarvisSpeaker(this)
            useVoskForCommands = false
            alive = true
            isRunning = true
            loadModelAndStart()
        }
        // NOT_STICKY: Android 14 forbids restarting the microphone from the background,
        // so if the system kills JARVIS you restart it from the app.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cleanUp()
        super.onDestroy()
    }

    // ─── loop ────────────────────────────────────────────────────────────────

    private fun loadModelAndStart() {
        val path = VoskModelManager.modelPath(this)
        if (path == null) {
            setStatus("Voice model not downloaded. Open JARVIS and download it.")
            shutdown()
            return
        }
        setStatus("Starting up…")
        scope.launch {
            try {
                val m = withContext(Dispatchers.IO) { Model(path) }
                if (!alive) { m.close(); return@launch }
                model = m
                val e = VoskEars(m, path)
                ears = e
                Log.d(TAG, "Wake word mode: ${if (e.exactMode) "exact" else "backup (transcribe)"}")
                waitForWakeWord()
            } catch (ex: Exception) {
                Log.e(TAG, "Model load failed", ex)
                setStatus("Could not load the voice model. Try downloading it again.")
                shutdown()
            }
        }
    }

    private fun waitForWakeWord() {
        if (!alive) return
        pending = null
        pendingConfirm = null
        setStatus(STATUS_IDLE)
        if (inSession) hideSessionUi()
        ears?.listenForWakeWord(
            onWake = { onWakeWord() },
            onError = { msg ->
                setStatus("$msg — retrying…")
                handler.postDelayed({ if (alive) waitForWakeWord() }, 5000)
            }
        )
    }

    private fun onWakeWord() {
        if (!alive) return
        ears?.stop()   // free the microphone
        setStatus("At your service")
        showSessionUi()
        speaker.speak(JarvisPrefs.GREETING) { listenForCommand() }
    }

    private fun listenForCommand() {
        if (!alive) return
        setStatus("Listening…")
        if (useVoskForCommands) {
            listenWithVosk()
            return
        }
        commandListener.listen { outcome ->
            when (outcome) {
                is CommandListener.Outcome.Heard -> process(outcome.text)
                is CommandListener.Outcome.Silence -> waitForWakeWord()
                is CommandListener.Outcome.Failed -> {
                    // Phone speech recognition not usable (often in background / offline):
                    // switch to the offline Vosk model for the rest of this session.
                    Log.w(TAG, "Phone recognizer failed (${outcome.reason}); using Vosk")
                    useVoskForCommands = true
                    listenWithVosk()
                }
            }
        }
    }

    private fun listenWithVosk() {
        ears?.listenOnce(8000) { text ->
            if (!alive) return@listenOnce
            ears?.stop()
            if (text.isBlank()) waitForWakeWord() else process(text)
        } ?: waitForWakeWord()
    }

    private fun process(text: String) {
        if (!alive) return
        setStatus("Thinking…", "“$text”")
        val confirm = pendingConfirm
        if (confirm != null) {
            pendingConfirm = null
            if (brain.isYes(text)) act(confirm()) else say("Okay, cancelled, sir.")
            return
        }
        val choice = pending
        if (choice != null) {
            pending = null
            if (brain.isCancel(text)) { say("Very well, sir. Cancelled."); return }
            val picked = brain.pickContact(text, choice.options)
            if (picked == null) say("Sorry sir, I didn't catch which one. Cancelled.")
            else act(choice.onPick(picked))
            return
        }
        scope.launch { act(brain.handle(text)) }
    }

    private fun act(reply: JarvisBrain.Reply) {
        if (!alive) return
        when (reply) {
            is JarvisBrain.Reply.Say -> say(reply.text)
            is JarvisBrain.Reply.Goodbye -> speaker.speak(reply.text) { shutdown() }
            is JarvisBrain.Reply.ChooseContact -> {
                pending = PendingChoice(reply.options, reply.onPick)
                setStatus("Which contact?", reply.question)
                speaker.speak(reply.question) { listenForCommand() }
            }
            is JarvisBrain.Reply.Call -> {
                setStatus("Calling ${reply.contact.nom}")
                speaker.speak("Calling ${reply.contact.nom}.") {
                    val error = placeCall(reply.contact.telephone)
                    if (error != null) say(error) else waitForWakeWord()
                }
            }
            is JarvisBrain.Reply.SendWhatsApp -> sendWhatsApp(reply.contact, reply.message)
            is JarvisBrain.Reply.Confirm -> {
                pendingConfirm = reply.onYes
                setStatus("Waiting for yes or no", reply.question)
                speaker.speak(reply.question) { listenForCommand() }
            }
        }
    }

    private fun sendWhatsApp(contact: Contact, message: String) {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (km.isKeyguardLocked) {
            say("Please unlock your phone first, sir. I can't work WhatsApp from the lock screen.")
            return
        }
        setStatus("Sending WhatsApp", "Sending to ${contact.nom}…")
        speaker.speak("Sending to ${contact.nom}.") {
            WhatsAppSender.send(this, contact.telephone, message) { outcome ->
                if (alive) {
                    say(
                        when (outcome) {
                            WhatsAppSender.Outcome.SENT -> "Done, sir. Message sent to ${contact.nom}."
                            WhatsAppSender.Outcome.CHAT_OPENED_NOT_SENT ->
                                "I opened the chat with ${contact.nom}, but I can't press send yet. " +
                                    "Please turn on JARVIS in Accessibility settings."
                            WhatsAppSender.Outcome.NOT_INSTALLED -> "WhatsApp isn't installed, sir."
                            WhatsAppSender.Outcome.FAILED ->
                                "I couldn't send that, sir. Check that JARVIS may display over other apps, " +
                                    "and that ${contact.nom} uses WhatsApp."
                        }
                    )
                }
            }
        }
    }

    /** Speak, then go back to waiting for the wake word. */
    private fun say(text: String) {
        setStatus("Speaking", text)
        speaker.speak(text) { waitForWakeWord() }
    }

    /** Returns null on success, or a sentence to speak on failure. */
    private fun placeCall(number: String): String? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) return "I need permission to make phone calls. Please open JARVIS and allow it."
        val clean = number.replace(Regex("[^0-9+*#]"), "")
        if (clean.isEmpty()) return "That contact has no valid phone number, sir."
        return try {
            val telecom = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecom.placeCall(Uri.fromParts("tel", clean, null), Bundle())
            null
        } catch (e: SecurityException) {
            "Android blocked the call, sir. Please check the phone permission."
        } catch (e: Exception) {
            Log.e(TAG, "Call failed", e)
            "I couldn't place the call, sir."
        }
    }

    // ─── on-screen JARVIS (orb when locked, top bar when unlocked) ───────────

    private fun showSessionUi() {
        inSession = true
        if (!Settings.canDrawOverlays(this)) return   // voice only
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (km.isKeyguardLocked || !pm.isInteractive) {
            try {
                startActivity(
                    Intent(this, JarvisOrbActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Orb screen blocked", e)
            }
        } else {
            JarvisOverlay.show(this, lastStatus)
        }
    }

    private fun hideSessionUi() {
        inSession = false
        handler.postDelayed({ if (!inSession) JarvisOverlay.hide() }, 1500)
    }

    // ─── foreground notification ─────────────────────────────────────────────

    private fun goForeground(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "JARVIS listening", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while JARVIS is using the microphone"
                setShowBadge(false)
            }
        )
        return try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification("Starting up…"),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            )
            true
        } catch (e: Exception) {
            // Android 14+: the mic service must be started while the JARVIS screen is open.
            Log.e(TAG, "startForeground failed", e)
            setStatus("Android didn't allow background listening. Open JARVIS and press Start.")
            stopSelf()
            false
        }
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_jarvis_mic)
            .setContentTitle("JARVIS is listening")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, JarvisActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .addAction(
                R.drawable.ic_close, "Stop listening",
                PendingIntent.getService(
                    this, 1, Intent(this, JarvisService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()

    /**
     * [notif] goes in the notification (short and generic, since it can show on the lock screen).
     * [display] goes to the JARVIS screens, top bar and orb (can be the full reply).
     */
    private fun setStatus(notif: String, display: String = notif) {
        lastStatus = display
        listeners.forEach { it(display) }
        if (inSession) JarvisOverlay.update(display)
        if (alive) {
            try {
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(notif))
            } catch (_: Exception) { }
        }
    }

    // ─── stopping ────────────────────────────────────────────────────────────

    private fun shutdown() {
        cleanUp()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanUp() {
        JarvisOverlay.hide()
        inSession = false
        if (!alive && model == null) { isRunning = false; return }
        alive = false
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        commandListener.cancel()
        ears?.stop(); ears = null
        try { model?.close() } catch (_: Exception) { }
        model = null
        if (::speaker.isInitialized) speaker.shutdown()
        lastStatus = STATUS_OFF
        listeners.forEach { it(STATUS_OFF) }
    }

    companion object {
        private const val TAG = "JARVIS_SERVICE"
        private const val CHANNEL_ID = "jarvis_listening"
        private const val NOTIF_ID = 4242
        const val ACTION_STOP = "com.kevin.jarvis.STOP"
        const val STATUS_IDLE = "Listening for “Jarvis”"
        const val STATUS_OFF = "Off"

        @Volatile var isRunning = false
        @Volatile var lastStatus = STATUS_OFF

        private val listeners = CopyOnWriteArraySet<(String) -> Unit>()

        /** Screens register here to show live status. Called on the main thread. */
        fun addStatusListener(l: (String) -> Unit) { listeners.add(l) }
        fun removeStatusListener(l: (String) -> Unit) { listeners.remove(l) }

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, JarvisService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, JarvisService::class.java).setAction(ACTION_STOP))
        }
    }
}
