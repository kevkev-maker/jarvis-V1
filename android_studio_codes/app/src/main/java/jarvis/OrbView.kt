package com.example.myapp.jarvis

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.sin

/**
 * The glowing blue JARVIS orb. It breathes gently while JARVIS is active.
 * If the phone's "Remove animations" setting is on, Android freezes it automatically.
 */
class OrbView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private var animator: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2200
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f
        if (r <= 0f) return
        val pulse = 0.86f + 0.14f * sin(phase * 2 * PI).toFloat()
        paint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(0xFFC8F4FF.toInt(), 0xFF3AA8FF.toInt(), 0xFF1D4BFF.toInt(), 0x001D4BFF),
            floatArrayOf(0f, 0.35f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r * pulse, paint)
    }
}
