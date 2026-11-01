package com.example.myapp.jarvis

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Siri-style full screen with the blue orb. JARVIS opens it when you call him
 * while the phone is locked or the screen is off. It shows over the lock screen,
 * turns the screen on, and closes by itself when JARVIS goes back to waiting.
 * Tap anywhere to close it early (JARVIS keeps working).
 */
class JarvisOrbActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private val listener: (String) -> Unit = { s -> runOnUiThread { onStatus(s) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = JarvisUi.BG
        window.navigationBarColor = JarvisUi.BG

        val dp = { v: Int -> JarvisUi.dp(this, v) }
        val root = FrameLayout(this).apply { setBackgroundColor(JarvisUi.BG) }

        status = TextView(this).apply {
            setTextColor(JarvisUi.TEXT)
            textSize = 22f
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
            setPadding(dp(32), 0, dp(32), 0)
        }
        root.addView(status, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ).apply { bottomMargin = dp(120) })

        root.addView(OrbView(this), FrameLayout.LayoutParams(dp(200), dp(200),
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = dp(72) })

        root.setOnClickListener { finish() }
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        JarvisService.addStatusListener(listener)
        onStatus(JarvisService.lastStatus)
    }

    override fun onStop() {
        JarvisService.removeStatusListener(listener)
        handler.removeCallbacksAndMessages(null)
        super.onStop()
    }

    private fun onStatus(s: String) {
        status.text = s
        if (isIdle(s)) {
            handler.postDelayed({ if (isIdle(JarvisService.lastStatus)) finish() }, 1_500)
        }
    }

    private fun isIdle(s: String) = s == JarvisService.STATUS_IDLE || s == JarvisService.STATUS_OFF
}
