package com.example.myapp.jarvis

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton

/** Shared look for the JARVIS screens (built in code to keep the change small). */
object JarvisUi {
    val BG = Color.parseColor("#0A0F1A")
    val CARD = Color.parseColor("#121A2B")
    val LINE = Color.parseColor("#22304A")
    val ACCENT = Color.parseColor("#4FD1E8")
    val TEXT = Color.parseColor("#E8EEF7")
    val MUTED = Color.parseColor("#8A99B3")
    val DANGER = Color.parseColor("#E8735A")

    fun dp(c: Context, v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), c.resources.displayMetrics).toInt()

    fun column(c: Context, padDp: Int = 20) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(c, padDp), dp(c, padDp), dp(c, padDp), dp(c, padDp))
    }

    fun title(c: Context, text: String) = TextView(c).apply {
        this.text = text
        setTextColor(TEXT)
        textSize = 28f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.12f
    }

    fun label(c: Context, text: String, sizeSp: Float = 15f, color: Int = TEXT) = TextView(c).apply {
        this.text = text
        setTextColor(color)
        textSize = sizeSp
        setLineSpacing(0f, 1.2f)
    }

    fun sectionHeader(c: Context, text: String) = label(c, text.uppercase(), 12f, MUTED).apply {
        letterSpacing = 0.1f
        setPadding(0, dp(c, 24), 0, dp(c, 8))
    }

    fun card(c: Context) = column(c, 16).apply {
        background = GradientDrawable().apply {
            setColor(CARD)
            cornerRadius = dp(c, 14).toFloat()
            setStroke(dp(c, 1), LINE)
        }
    }

    fun button(c: Context, text: String, primary: Boolean = false, onClick: (View) -> Unit) =
        MaterialButton(c).apply {
            this.text = text
            isAllCaps = false
            textSize = 15f
            cornerRadius = dp(c, 12)
            insetTop = 0; insetBottom = 0
            minHeight = dp(c, 52)
            if (primary) {
                backgroundTintList = ColorStateList.valueOf(ACCENT)
                setTextColor(BG)
            } else {
                backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                strokeColor = ColorStateList.valueOf(LINE)
                strokeWidth = dp(c, 1)
                setTextColor(TEXT)
            }
            setOnClickListener(onClick)
        }

    fun spaced(c: Context, v: View, topDp: Int = 10): View {
        v.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(c, topDp) }
        return v
    }

    fun centered(tv: TextView) = tv.apply { gravity = Gravity.CENTER }
}
