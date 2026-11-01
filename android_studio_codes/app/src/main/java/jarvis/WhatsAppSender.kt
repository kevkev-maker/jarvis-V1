package com.example.myapp.jarvis

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.myapp.AlexandraAccessibilityService

/**
 * Sends a WhatsApp message without you touching the phone:
 *  1. opens the chat with the text already typed (WhatsApp's own link),
 *  2. the existing accessibility service presses WhatsApp's Send button.
 * Needs: WhatsApp installed, JARVIS turned on in Accessibility settings,
 * "Display over other apps" allowed (so Android lets JARVIS open WhatsApp
 * from the background), and the phone unlocked.
 */
object WhatsAppSender {

    enum class Outcome { SENT, CHAT_OPENED_NOT_SENT, NOT_INSTALLED, FAILED }

    private const val TAG = "JARVIS_WA"
    private val PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")
    private val handler = Handler(Looper.getMainLooper())

    /** Phone number → digits with country code. 10-digit numbers are treated as Indian (+91). */
    fun normalize(raw: String): String? {
        val kept = raw.filter { it.isDigit() || it == '+' }
        if (kept.isEmpty()) return null
        val plus = kept.startsWith("+")
        val d = kept.filter { it.isDigit() }
        return when {
            plus -> d
            d.startsWith("00") -> d.drop(2)
            d.length == 10 -> "91$d"
            d.length == 11 && d.startsWith("0") -> "91" + d.drop(1)
            d.length >= 11 -> d
            else -> null
        }.takeIf { !it.isNullOrEmpty() && it.length >= 8 }
    }

    /** Must be called on the main thread. [onResult] is called on the main thread. */
    fun send(context: Context, rawNumber: String, message: String, onResult: (Outcome) -> Unit) {
        val ctx = context.applicationContext
        val number = normalize(rawNumber) ?: return onResult(Outcome.FAILED)
        val uri = Uri.parse("whatsapp://send?phone=$number&text=${Uri.encode(message)}")

        var opened: String? = null
        for (pkg in PACKAGES) {
            try {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                opened = pkg
                break
            } catch (e: ActivityNotFoundException) {
                // try the next WhatsApp version
            } catch (e: Exception) {
                Log.w(TAG, "Could not open $pkg: ${e.message}")
            }
        }
        val pkg = opened ?: return onResult(Outcome.NOT_INSTALLED)

        val service = AlexandraAccessibilityService.instance
        if (service == null) {
            onResult(Outcome.CHAT_OPENED_NOT_SENT)
            return
        }
        poll(service, pkg, 0, onResult)
    }

    /** Looks for WhatsApp's Send button for up to ~12 seconds. */
    private fun poll(
        service: AlexandraAccessibilityService,
        pkg: String,
        attempt: Int,
        onResult: (Outcome) -> Unit
    ) {
        if (attempt > 28) {
            Log.w(TAG, "Send button never appeared")
            onResult(Outcome.FAILED)
            return
        }
        handler.postDelayed({
            val root = try { service.rootInActiveWindow } catch (e: Exception) { null }
            val button = if (root != null && root.packageName?.toString() == pkg) findSend(root, pkg) else null
            if (button != null && click(button)) {
                handler.postDelayed({ onResult(Outcome.SENT) }, 700)
            } else {
                poll(service, pkg, attempt + 1, onResult)
            }
        }, if (attempt == 0) 1_500L else 400L)
    }

    private fun findSend(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? {
        root.findAccessibilityNodeInfosByViewId("$pkg:id/send").firstOrNull { it.isEnabled }?.let { return it }
        return root.findAccessibilityNodeInfosByText("Send").firstOrNull { n ->
            n.contentDescription?.toString().equals("Send", ignoreCase = true)
        }
    }

    /** Clicks the node, or its nearest clickable parent. */
    private fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        repeat(3) {
            val cur = n ?: return false
            if (cur.isClickable) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = cur.parent
        }
        return false
    }
}
