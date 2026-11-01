package com.example.myapp.jarvis

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.myapp.R

/** Schedules reminders with Android's alarm system so they fire even if JARVIS is off. */
object ReminderScheduler {

    const val EXTRA_ID = "jarvis_reminder_id"
    private const val CHANNEL_ID = "jarvis_reminders"

    fun schedule(context: Context, r: MemoryStore.Reminder) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, r.id)
        val at = maxOf(r.at, System.currentTimeMillis() + 3_000)
        val exactAllowed = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (exactAllowed) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)   // may be a few minutes late
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** After a reboot Android forgets alarms, so we set them again. */
    fun rescheduleAll(context: Context) {
        MemoryStore.get(context).pendingReminders().forEach { schedule(context, it) }
    }

    private fun pendingIntent(context: Context, id: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context, id.toInt(),
            Intent(context, JarvisReminderReceiver::class.java).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    @SuppressLint("MissingPermission")
    fun showNotification(context: Context, r: MemoryStore.Reminder) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "JARVIS reminders", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, JarvisActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_jarvis_mic)
            .setContentTitle("Reminder")
            .setContentText(r.text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(10_000 + r.id.toInt(), n)
        } catch (e: SecurityException) {
            Log.w("JARVIS_REMIND", "Notification permission missing")
        }
    }
}

/** Fires when a reminder is due: shows a notification and says it aloud. */
class JarvisReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(ReminderScheduler.EXTRA_ID, -1)
        val store = MemoryStore.get(context)
        val r = store.pendingReminder(id) ?: return
        store.markDone(id)
        ReminderScheduler.showNotification(context, r)

        val pending = goAsync()
        val speaker = JarvisSpeaker(context)
        var finished = false
        val finish = {
            if (!finished) {
                finished = true
                speaker.shutdown()
                pending.finish()
            }
        }
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 9_000)   // Android allows ~10 s
        speaker.speak("Sir, a reminder. ${r.text}.") { finish() }
    }
}

/** Re-arms reminders after the phone restarts or the app is updated. */
class JarvisBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            ReminderScheduler.rescheduleAll(context)
        }
    }
}
