package com.phoneai.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.os.Build
import android.os.PowerManager
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Orion's face: an animated orb that shows idle, listening, thinking, speaking and acting. */
class OrbView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    enum class Mode { IDLE, LISTENING, THINKING, SPEAKING, ACTING, WAITING_APPROVAL }

    companion object {
        val CYAN = 0xFF38E1FF.toInt()
        val VIOLET = 0xFF7C5CFF.toInt()
        val OK = 0xFF3DDC97.toInt()
        val WARN = 0xFFFFB84D.toInt()
    }

    @Volatile
    var mode: Mode = Mode.IDLE
        set(v) {
            field = v
            postInvalidateOnAnimation()
        }

    /** 0..1 microphone loudness while listening. */
    @Volatile
    var level: Float = 0f

    /** Draws a dark disc behind the orb (used for the floating button). */
    var backing: Boolean = false

    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()
    private var smooth = 0f
    private var smoothPulse = 0.5f
    private var smoothSpeed = 20f
    private var wordPulse = 0f

    /** Speech engine callback: briefly brighten and expand the core on spoken words. */
    fun pulseMouth() {
        wordPulse = 1f
        postInvalidateOnAnimation()
    }

    /** Adaptive frame pacing for entry-level phones under power or thermal pressure. */
    private fun nextFrameDelayMs(): Long {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return 33L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val thermal = power.currentThermalStatus
            if (thermal >= PowerManager.THERMAL_STATUS_SEVERE) return 120L
            if (thermal >= PowerManager.THERMAL_STATUS_MODERATE) return 66L
        }
        return if (power.isPowerSaveMode) 66L else 33L
    }

    private fun accent(): Int = when (mode) {
        Mode.IDLE -> CYAN
        Mode.LISTENING -> OK
        Mode.THINKING -> VIOLET
        Mode.SPEAKING -> CYAN
        Mode.ACTING, Mode.WAITING_APPROVAL -> WARN
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f
        val t = (SystemClock.uptimeMillis() % 100000L) / 1000f
        val col = accent()

        val target = if (mode == Mode.LISTENING) level else 0f
        smooth += (target - smooth) * 0.25f

        var targetPulse: Float = when (mode) {
            Mode.IDLE -> 0.5f + 0.5f * sin(t * 1.6f)
            Mode.LISTENING -> 0.3f + smooth * 0.7f
            Mode.THINKING -> 0.5f + 0.5f * sin(t * 4f)
            Mode.SPEAKING -> 0.5f + 0.5f * sin(t * 9f) * (0.6f + 0.4f * sin(t * 3.3f))
            Mode.ACTING -> 0.5f + 0.5f * sin(t * 6f)
            Mode.WAITING_APPROVAL -> 0.5f + 0.5f * sin(t * 2f)
        }
        if (mode == Mode.SPEAKING) targetPulse = max(targetPulse, 0.62f + 0.38f * wordPulse)
        wordPulse *= 0.72f
        smoothPulse += (targetPulse - smoothPulse) * 0.20f
        val pulse = smoothPulse

        if (backing) {
            core.shader = null
            core.color = 0xE6070B12.toInt()
            canvas.drawCircle(cx, cy, r * 0.92f, core)
        }
        // soft outer glow
        glow.shader = RadialGradient(
            cx, cy, r, intArrayOf((col and 0x00FFFFFF) or 0x55000000, (col and 0x00FFFFFF) or 0x00000000),
            floatArrayOf(0.35f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, glow)

        // ripples while listening or speaking
        if (mode == Mode.LISTENING || mode == Mode.SPEAKING || mode == Mode.WAITING_APPROVAL) {
            for (i in 0 until 3) {
                val phase = ((t * 0.7f) + i / 3f) % 1f
                ring.strokeWidth = r * 0.025f
                ring.color = (col and 0x00FFFFFF) or (((1f - phase) * 140).toInt() shl 24)
                canvas.drawCircle(cx, cy, r * (0.45f + 0.5f * phase), ring)
            }
        }

        // rotating arcs
        val targetSpeed = when (mode) {
            Mode.THINKING -> 220f
            Mode.ACTING -> 140f
            Mode.WAITING_APPROVAL -> 45f
            Mode.IDLE -> 20f
            else -> 60f
        }
        smoothSpeed += (targetSpeed - smoothSpeed) * 0.16f
        val speed = smoothSpeed
        ring.strokeWidth = r * 0.05f
        ring.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 3) {
            val rad = r * (0.62f + i * 0.1f)
            rect.set(cx - rad, cy - rad, cx + rad, cy + rad)
            val dir = if (i % 2 == 0) 1f else -1f
            val start = (t * speed * dir + i * 120f) % 360f
            val sweep = if (mode == Mode.THINKING) 90f else if (mode == Mode.WAITING_APPROVAL) 35f else 55f
            ring.color = if (i == 1) VIOLET else col
            ring.alpha = if (mode == Mode.IDLE) 110 else if (mode == Mode.WAITING_APPROVAL) 255 else 220
            canvas.drawArc(rect, start, sweep, false, ring)
            canvas.drawArc(rect, start + 180f, sweep * 0.6f, false, ring)
        }

        // core sphere
        val coreR = r * (0.36f + 0.07f * pulse)
        core.shader = RadialGradient(
            cx - coreR * 0.3f, cy - coreR * 0.3f, coreR * 1.4f,
            intArrayOf(0xFFFFFFFF.toInt(), col, VIOLET),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, coreR, core)

        // speaking waveform across the core
        if (mode == Mode.SPEAKING) {
            bar.color = 0xCC04101C.toInt()
            bar.strokeWidth = coreR * 0.12f
            for (i in -3..3) {
                val amp = (0.25f + 0.75f * abs01(sin(t * 8f + i * 0.9f))) * coreR * 0.6f
                val x = cx + i * coreR * 0.24f
                canvas.drawLine(x, cy - amp / 2f, x, cy + amp / 2f, bar)
            }
        }
        // acting: small orbiting dot
        if (mode == Mode.ACTING) {
            val ang = (t * 2f * PI).toFloat()
            bar.color = 0xFFFFFFFF.toInt()
            canvas.drawCircle(cx + cos(ang) * r * 0.86f, cy + sin(ang) * r * 0.86f, r * 0.05f, bar)
        }
        glow.shader = null
        core.shader = null
        postInvalidateDelayed(nextFrameDelayMs())
    }

    private fun abs01(v: Float): Float = max(v, -v)

    @Suppress("unused")
    private fun unusedGradient(): Shader = LinearGradient(0f, 0f, 1f, 1f, CYAN, VIOLET, Shader.TileMode.CLAMP)
}
