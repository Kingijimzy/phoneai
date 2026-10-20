package com.phoneai.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A lightweight, animated liquid-glass orb inspired by the ChatAI Dribbble reference.
 * It is drawn with Canvas (no heavy 3D renderer or downloaded asset), keeping entry-level
 * Android devices responsive. Modes subtly alter the fluid motion and glow.
 */
class OrbView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    enum class Mode { IDLE, LISTENING, THINKING, SPEAKING, ACTING, WAITING_APPROVAL }

    companion object {
        val CYAN = Theme.CYAN
        val VIOLET = 0xFF016856.toInt()
        val OK = Theme.OK
        val WARN = Theme.WARN
    }

    @Volatile var mode: Mode = Mode.IDLE
        set(v) { field = v; postInvalidateOnAnimation() }
    @Volatile var level: Float = 0f
    var backing: Boolean = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fluid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()
    private val spherePath = Path()
    private var smoothLevel = 0f
    private var smoothPulse = 0.5f
    private var wordPulse = 0f

    fun pulseMouth() { wordPulse = 1f; postInvalidateOnAnimation() }

    private fun nextFrameDelayMs(): Long {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return 40L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val thermal = power.currentThermalStatus
            if (thermal >= PowerManager.THERMAL_STATUS_SEVERE) return 120L
            if (thermal >= PowerManager.THERMAL_STATUS_MODERATE) return 66L
        }
        return if (power.isPowerSaveMode) 80L else 33L
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); postInvalidateOnAnimation() }
    override fun onDetachedFromWindow() { animate().cancel(); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) postInvalidateOnAnimation()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) postInvalidateOnAnimation()
    }

    private fun accent(): Int = when (mode) {
        Mode.IDLE, Mode.SPEAKING -> CYAN
        Mode.LISTENING -> OK
        Mode.THINKING -> VIOLET
        Mode.ACTING, Mode.WAITING_APPROVAL -> WARN
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) * 0.43f
        if (r <= 1f) return
        val t = SystemClock.uptimeMillis() / 1000f
        val col = accent()
        val targetLevel = if (mode == Mode.LISTENING) level.coerceIn(0f, 1f) else 0f
        smoothLevel += (targetLevel - smoothLevel) * 0.22f
        val motion = when (mode) {
            Mode.IDLE -> 0.5f + 0.5f * sin(t * 1.15f)
            Mode.LISTENING -> 0.20f + smoothLevel * 0.80f
            Mode.THINKING -> 0.5f + 0.5f * sin(t * 3.4f)
            Mode.SPEAKING -> 0.5f + 0.5f * sin(t * 6.2f)
            Mode.ACTING -> 0.5f + 0.5f * sin(t * 4.0f)
            Mode.WAITING_APPROVAL -> 0.5f + 0.5f * sin(t * 1.7f)
        }
        smoothPulse += (motion - smoothPulse) * 0.14f
        wordPulse *= 0.76f
        val breathe = smoothPulse

        if (backing) {
            paint.shader = null
            paint.color = 0xFF02080A.toInt()
            canvas.drawCircle(cx, cy, min(w, h) * 0.49f, paint)
        }

        // Soft atmospheric halo breathes with the core; extra bloom is concentrated around
        // the sphere instead of filling the whole screen with a flat neon disc.
        val haloRadius = min(w, h) * 0.49f
        paint.shader = RadialGradient(cx, cy, haloRadius,
            intArrayOf((col and 0x00FFFFFF) or ((38 + 30 * breathe).toInt() shl 24),
                (col and 0x00FFFFFF) or 0x1A000000, 0x0000FCB6),
            floatArrayOf(0.24f, 0.70f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, haloRadius, paint)
        paint.shader = null

        // A tiny floating drift makes the core feel alive without shifting its layout bounds.
        val driftX = sin(t * 0.58f) * r * 0.025f
        val driftY = cos(t * 0.73f) * r * 0.018f
        val coreX = cx + driftX
        val coreY = cy + driftY
        val sphereR = r * (0.91f + 0.035f * breathe)
        val bloomR = sphereR * (1.12f + 0.065f * breathe)
        paint.shader = RadialGradient(coreX, coreY, bloomR,
            intArrayOf((col and 0x00FFFFFF) or ((45 + 28 * breathe).toInt() shl 24),
                (col and 0x00FFFFFF) or 0x1A000000, 0x0000FCB6),
            floatArrayOf(0.48f, 0.76f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(coreX, coreY, bloomR, paint)
        paint.shader = null

        // The reference's centerpiece is a single glossy, translucent turquoise sphere.
        spherePath.reset()
        spherePath.addCircle(coreX, coreY, sphereR, Path.Direction.CW)
        paint.shader = RadialGradient(
            coreX - sphereR * 0.30f, coreY - sphereR * 0.40f, sphereR * 1.65f,
            intArrayOf(0xFFF1FFFC.toInt(), 0xFF72FFE7.toInt(), 0xFF0AD9C0.toInt(), 0xFF006D68.toInt(), 0xFF03191C.toInt()),
            floatArrayOf(0f, 0.18f, 0.48f, 0.79f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(coreX, coreY, sphereR, paint)
        paint.shader = null

        val save = canvas.save()
        canvas.clipPath(spherePath)

        // Broad curved reflections give the sphere a liquid-glass / 3D refractive feel.
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            coreX - sphereR * 0.34f, coreY - sphereR * 0.55f, sphereR * 0.82f,
            intArrayOf(0xD9FFFFFF.toInt(), 0x66BFFFF6, 0x00FFFFFF),
            floatArrayOf(0f, 0.38f, 1f), Shader.TileMode.CLAMP)
        canvas.drawOval(coreX - sphereR * 0.72f, coreY - sphereR * 0.88f,
            coreX + sphereR * 0.32f, coreY - sphereR * 0.22f, paint)
        paint.shader = null

        // Moving inner currents: restrained ribbons, not sci-fi orbit rings.
        fluid.strokeWidth = max(1.1f, sphereR * 0.045f)
        fluid.color = 0xA6C8FFF7.toInt()
        val shift = sin(t * 0.92f) * sphereR * (0.11f + 0.025f * breathe)
        val drift = cos(t * 0.67f) * sphereR * 0.09f
        val ripple = sin(t * 1.27f + 0.7f) * sphereR * 0.055f
        val current1 = Path().apply {
            moveTo(coreX - sphereR * 1.05f, coreY - sphereR * 0.10f + shift)
            cubicTo(coreX - sphereR * 0.46f + drift, coreY - sphereR * 0.70f + ripple,
                coreX + sphereR * 0.12f - drift, coreY - sphereR * 0.76f + shift * 0.25f,
                coreX + sphereR * 1.03f, coreY - sphereR * 0.08f - shift)
        }
        canvas.drawPath(current1, fluid)
        fluid.strokeWidth = max(0.8f, sphereR * 0.022f)
        fluid.color = 0x887BFFF0.toInt()
        val current2 = Path().apply {
            moveTo(coreX - sphereR * 0.95f, coreY + sphereR * 0.35f - shift)
            cubicTo(coreX - sphereR * 0.25f - drift, coreY + sphereR * 0.02f + shift * 0.3f,
                coreX + sphereR * 0.25f + drift, coreY + sphereR * 0.83f + ripple,
                coreX + sphereR * 1.02f, coreY + sphereR * 0.25f + shift)
        }
        canvas.drawPath(current2, fluid)

        // Fine crescent highlight along the top edge creates the wet, polished finish.
        fluid.strokeWidth = max(1f, sphereR * 0.035f)
        fluid.color = 0xCCF5FFFE.toInt()
        rect.set(coreX - sphereR * 0.72f, coreY - sphereR * 0.91f, coreX + sphereR * 0.62f, coreY + sphereR * 0.38f)
        canvas.drawArc(rect, 208f + sin(t * 0.4f) * 4f, 104f, false, fluid)

        // Tiny specular glint.
        paint.shader = RadialGradient(coreX - sphereR * 0.34f, coreY - sphereR * 0.63f, sphereR * 0.24f,
            intArrayOf(0xFFFFFFFF.toInt(), 0x66FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.38f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(coreX - sphereR * 0.30f, coreY - sphereR * 0.59f, sphereR * 0.24f, paint)
        paint.shader = null
        canvas.restoreToCount(save)

        // A few restrained orbit sparks give the core depth while keeping Canvas work cheap.
        val orbitSpeed = when (mode) {
            Mode.THINKING -> 1.9f
            Mode.ACTING -> 1.5f
            Mode.SPEAKING -> 1.25f
            else -> 0.62f
        }
        for (i in 0 until 3) {
            val angle = t * orbitSpeed + i * (2f * PI.toFloat() / 3f)
            val orbit = r * (1.08f + 0.05f * sin(t * 1.4f + i))
            val alpha = (110 + 75 * (0.5f + 0.5f * sin(t * 2f + i))).toInt().coerceIn(0, 220)
            paint.shader = null
            paint.color = (col and 0x00FFFFFF) or (alpha shl 24)
            canvas.drawCircle(cx + cos(angle) * orbit, cy + sin(angle) * orbit * 0.82f,
                max(1.4f, r * (if (i == 0) 0.026f else 0.016f)), paint)
        }

        // Listening/speaking/approval rings; thinking also gets a quiet scanner pulse.
        if (mode == Mode.LISTENING || mode == Mode.SPEAKING || mode == Mode.WAITING_APPROVAL || mode == Mode.THINKING) {
            for (i in 0 until 2) {
                val phase = ((t * (if (mode == Mode.THINKING) 0.70f else 0.48f)) + i * 0.5f) % 1f
                ring.strokeWidth = max(1f, r * 0.018f)
                ring.color = (col and 0x00FFFFFF) or (((1f - phase) * 100).toInt() shl 24)
                canvas.drawCircle(cx, cy, r * (1.04f + 0.22f * phase), ring)
            }
        }
        if (mode == Mode.ACTING) {
            val angle = (t * 2f * PI).toFloat()
            paint.color = 0xFFF1FFFC.toInt()
            canvas.drawCircle(cx + cos(angle) * r * 1.10f, cy + sin(angle) * r * 1.10f, max(1.5f, r * 0.045f), paint)
        }
        paint.shader = null
        fluid.shader = null
        if (isShown && windowVisibility == VISIBLE) postInvalidateDelayed(nextFrameDelayMs())
    }
}
