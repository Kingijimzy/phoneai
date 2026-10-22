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
 * A lightweight monochrome assistant core with classic graphite and silver orb styling.
 * It is drawn with Canvas (no heavy 3D renderer or downloaded asset), keeping entry-level
 * Android devices responsive. Modes subtly alter the fluid motion and glow.
 */
class OrbView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    enum class Mode { IDLE, LISTENING, THINKING, SPEAKING, ACTING, WAITING_APPROVAL }

    companion object {
        val CYAN = Theme.CYAN
        val VIOLET = 0xFF858585.toInt()
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

    private var touchX = 0f
    private var touchY = 0f
    private var touchEnergy = 0f
    private var waveOffset = 0f
    private val clip = Path()
    private val wavePath = Path()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                touchX = event.x
                touchY = event.y
                touchEnergy = 1f
                waveOffset += .35f
                parent?.requestDisallowInterceptTouchEvent(true)
                postInvalidateOnAnimation()
                return true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dx = event.x - touchX
                val dy = event.y - touchY
                waveOffset += dx / max(1f, width.toFloat()) * 2.4f
                touchEnergy = (touchEnergy + kotlin.math.abs(dy) / max(1f, height.toFloat())).coerceIn(0f, 1.6f)
                touchX = event.x
                touchY = event.y
                postInvalidateOnAnimation()
                return true
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == android.view.MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        touchEnergy = 1f
        waveOffset += .18f
        postInvalidateOnAnimation()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w * .5f
        val cy = h * .5f
        val r = min(w, h) * .425f
        if (r < 2f) return

        val time = SystemClock.uptimeMillis() / 1000f
        val col = accent()
        val levelTarget = when (mode) {
            Mode.LISTENING -> level.coerceIn(0f, 1f)
            Mode.SPEAKING -> .42f + .25f * (0.5f + .5f * sin(time * 5f))
            Mode.THINKING -> .48f
            Mode.ACTING -> .55f
            Mode.WAITING_APPROVAL -> .2f
            Mode.IDLE -> .12f
        }
        smoothLevel += (levelTarget - smoothLevel) * .16f
        touchEnergy *= .94f
        val energy = (smoothLevel + touchEnergy * .42f).coerceIn(0f, 1.3f)
        val speed = when (mode) {
            Mode.IDLE -> .65f
            Mode.LISTENING -> 1.2f + smoothLevel
            Mode.THINKING -> 1.8f
            Mode.SPEAKING -> 2.2f
            Mode.ACTING -> 1.55f
            Mode.WAITING_APPROVAL -> .8f
        }
        val radius = r * (1f + .012f * sin(time * 1.5f) + touchEnergy * .012f)

        // Soft halo and a dark glass lens give the waves a premium, dimensional frame.
        glowPaint.shader = RadialGradient(cx, cy, radius * 1.42f,
            intArrayOf((col and 0x00FFFFFF) or ((38 + (energy * 26).toInt()).coerceAtMost(74) shl 24),
                (col and 0x00FFFFFF) or 0x16000000, 0x00000000),
            floatArrayOf(0f, .64f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, radius * 1.42f, glowPaint)
        glowPaint.shader = null

        paint.shader = RadialGradient(cx - radius * .22f, cy - radius * .28f, radius * 1.55f,
            intArrayOf(0xFF292929.toInt(), 0xFF141414.toInt(), 0xFF080808.toInt()),
            floatArrayOf(0f, .66f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, radius, paint)
        paint.shader = null

        clip.reset()
        clip.addCircle(cx, cy, radius * .965f, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(clip)

        // Subtle glass sheen.
        paint.shader = RadialGradient(cx - radius * .30f, cy - radius * .58f, radius * 1.2f,
            intArrayOf(0x244A4A4A, 0x102A2A2A, 0x00000000),
            floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, radius, paint)
        paint.shader = null

        // Three independently moving liquid bands. The bands react to mic level, mode,
        // and finger movement; no 3D renderer or per-frame bitmap allocation is needed.
        val base = cy + radius * (.12f - energy * .10f)
        val amplitudes = floatArrayOf(.105f, .078f, .052f)
        val phases = floatArrayOf(0f, 2.05f, 4.1f)
        val alpha = intArrayOf(210, 150, 100)
        for (layer in 0..2) {
            val amp = radius * (amplitudes[layer] + energy * (.075f - layer * .012f))
            wavePath.reset()
            val points = 52
            for (i in 0..points) {
                val x = cx - radius + radius * 2f * i / points
                val nx = (x - (cx - radius)) / (radius * 2f)
                val envelope = sin(PI.toFloat() * nx).coerceAtLeast(0f).let { .32f + .68f * it }
                val y = base + sin(nx * 2f * PI.toFloat() + time * speed + phases[layer] + waveOffset) * amp * envelope +
                    sin(nx * 4f * PI.toFloat() - time * speed * .56f + phases[layer]) * amp * .20f
                if (i == 0) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
            }
            wavePaint.color = (col and 0x00FFFFFF) or (alpha[layer] shl 24)
            wavePaint.strokeWidth = max(1.3f, radius * (if (layer == 0) .018f else .011f))
            canvas.drawPath(wavePath, wavePaint)

            // A low-opacity fill beneath the primary wave gives the liquid a glassy depth.
            if (layer == 0) {
                wavePath.lineTo(cx + radius, cy + radius)
                wavePath.lineTo(cx - radius, cy + radius)
                wavePath.close()
                paint.color = (col and 0x00FFFFFF) or 0x18000000
                canvas.drawPath(wavePath, paint)
            }
        }

        // Small travelling highlight across the liquid surface.
        val dotX = cx + sin(time * speed * .7f + waveOffset) * radius * .68f
        val dotY = base + sin(time * speed + waveOffset) * radius * .12f
        glowPaint.shader = RadialGradient(dotX, dotY, radius * .10f,
            intArrayOf(0xDFFFFFFF.toInt(), (col and 0x00FFFFFF) or 0x55000000, 0x00000000),
            floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(dotX, dotY, radius * .10f, glowPaint)
        glowPaint.shader = null
        canvas.restoreToCount(save)

        // Fine glass rim and a restrained moving highlight.
        ring.strokeWidth = max(1.2f, radius * .012f)
        ring.color = 0x60707070
        canvas.drawCircle(cx, cy, radius * .965f, ring)
        ring.strokeWidth = max(1f, radius * .008f)
        ring.color = (col and 0x00FFFFFF) or 0x8A000000.toInt()
        val sweepStart = (time * 25f + waveOffset * 40f) % 360f
        rect.set(cx - radius * 1.01f, cy - radius * 1.01f, cx + radius * 1.01f, cy + radius * 1.01f)
        canvas.drawArc(rect, sweepStart, 66f, false, ring)

        paint.shader = null
        glowPaint.shader = null
        wavePaint.shader = null
        fluid.shader = null
        if (isShown && windowVisibility == VISIBLE) postInvalidateDelayed(nextFrameDelayMs())
    }
}
