package com.example.myapp.jarvis

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Small JARVIS pill at the very top of the screen (over the status bar),
 * shown while you talk to JARVIS and the phone is unlocked.
 * Needs the "Display over other apps" permission. Touches pass straight through it.
 * Call only from the main thread.
 */
object JarvisOverlay {

    private var root: LinearLayout? = null
    private var label: TextView? = null

    fun show(context: Context, text: String) {
        if (root != null) { update(text); return }
        val app = context.applicationContext
        if (!Settings.canDrawOverlays(app)) return
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = app.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val orb = OrbView(app).apply {
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
        }
        val tv = TextView(app).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = app.resources.displayMetrics.widthPixels - dp(110)
            this.text = text
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(10) }
        }
        val pill = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(18), dp(6))
            background = GradientDrawable().apply {
                setColor(0xF20A0F1A.toInt())
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), 0xFF3AA8FF.toInt())
            }
            addView(orb)
            addView(tv)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(6)
        }
        try {
            wm.addView(pill, lp)
            root = pill
            label = tv
        } catch (e: Exception) {
            Log.e("JARVIS_OVERLAY", "Could not show overlay", e)
        }
    }

    fun update(text: String) {
        label?.text = text
    }

    fun hide() {
        val v = root ?: return
        try {
            (v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Exception) { }
        root = null
        label = null
    }
}
