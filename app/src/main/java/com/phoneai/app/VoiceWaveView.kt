package com.phoneai.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.abs
import kotlin.math.sin

/** Lightweight audio-inspired bars; deliberately not an orb or a 3D scene. */
class VoiceWaveView(context: Context) : View(context) {
    var mode: OrbView.Mode = OrbView.Mode.IDLE
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var frame = 0
    private val cyan = 0xFF00FCB6.toInt()
    private val teal = 0xFF15EAD3.toInt()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val count = 35
        val gap = width * 0.012f
        val barW = (width * 0.78f - gap * (count - 1)) / count
        val total = barW * count + gap * (count - 1)
        val left = (width - total) / 2f
        val mid = height / 2f
        val speed = when (mode) {
            OrbView.Mode.LISTENING -> 0.38
            OrbView.Mode.THINKING -> 0.12
            OrbView.Mode.SPEAKING -> 0.52
            OrbView.Mode.ACTING -> 0.2
            else -> 0.08
        }
        val energy = when (mode) {
            OrbView.Mode.LISTENING, OrbView.Mode.SPEAKING -> 1.0
            OrbView.Mode.THINKING, OrbView.Mode.ACTING -> 0.62
            else -> 0.32
        }
        for (i in 0 until count) {
            val waveA = abs(sin(i * 0.42 + frame * speed))
            val waveB = abs(sin(i * 0.17 - frame * speed * 0.6))
            val envelope = 0.18 + 0.82 * (waveA * 0.66 + waveB * 0.34)
            val h = (height * 0.68f * envelope * energy).toFloat().coerceAtLeast(height * 0.055f)
            val x = left + i * (barW + gap)
            paint.color = if (i % 4 == 0) teal else cyan
            paint.alpha = (120 + envelope * 135).toInt().coerceIn(0, 255)
            paint.strokeWidth = barW.coerceAtLeast(2f)
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(x + barW / 2f, mid - h / 2f, x + barW / 2f, mid + h / 2f, paint)
        }
        frame++
        postInvalidateDelayed(if (mode == OrbView.Mode.IDLE) 180L else 70L)
    }
}
