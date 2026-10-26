package com.phoneai.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Compact monochrome 7x7 dot-matrix control for the floating quick-command panel.
 * The old glossy sphere is intentionally removed; the full live-call view owns the larger
 * Dot Matrix / Eyes visualizer. Idle draws once, while active modes animate only as needed.
 */
class OrbView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    enum class Mode { IDLE, LISTENING, THINKING, SPEAKING, ACTING, WAITING_APPROVAL, ERROR }

    companion object {
        // Kept as a compatibility constant for existing callers; the visual system is neutral silver.
        val CYAN = 0xFFBFC5CC.toInt()
        val VIOLET = 0xFFBFC5CC.toInt()
        val OK = Theme.OK
        val WARN = Theme.WARN
    }

    @Volatile
    var mode: Mode = Mode.IDLE
        set(value) {
            field = value
            contentDescription = when (value) {
                Mode.IDLE -> "Orion idle"
                Mode.LISTENING -> "Orion listening"
                Mode.THINKING -> "Orion thinking"
                Mode.SPEAKING -> "Orion speaking"
                Mode.ACTING -> "Orion performing a task"
                Mode.WAITING_APPROVAL -> "Orion waiting for approval"
                Mode.ERROR -> "Orion task paused after an error"
            }
            postInvalidateOnAnimation()
        }

    @Volatile
    var level: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            if (mode == Mode.LISTENING || mode == Mode.SPEAKING) postInvalidateOnAnimation()
        }

    var backing: Boolean = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var smoothLevel = 0f
    private var touchEnergy = 0f
    private var wordPulse = 0f
    private var touchX = 0f
    private var touchY = 0f
    private var waveOffset = 0f

    init {
        contentDescription = "Orion idle"
    }

    fun pulseMouth() {
        wordPulse = 1f
        postInvalidateOnAnimation()
    }

    private fun nextFrameDelayMs(): Long {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return 66L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (power.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_CRITICAL,
                PowerManager.THERMAL_STATUS_EMERGENCY,
                PowerManager.THERMAL_STATUS_SHUTDOWN,
                PowerManager.THERMAL_STATUS_SEVERE -> return 160L
                PowerManager.THERMAL_STATUS_MODERATE -> return 90L
            }
        }
        return if (power.isPowerSaveMode) 100L else 50L
    }

    private fun shouldAnimate(): Boolean =
        isAttachedToWindow && isShown && visibility == VISIBLE && windowVisibility == VISIBLE

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(null)
        animate().cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) postInvalidateOnAnimation()
        else removeCallbacks(null)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) postInvalidateOnAnimation()
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
                touchEnergy = (touchEnergy + abs(dy) / max(1f, height.toFloat())).coerceIn(0f, 1.6f)
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
        if (w < 2f || h < 2f) return

        val time = SystemClock.uptimeMillis() / 1000f
        val energyTarget = when (mode) {
            Mode.IDLE -> 0f
            Mode.LISTENING -> level
            Mode.THINKING -> .48f
            Mode.SPEAKING -> .30f + .30f * (.5f + .5f * sin(time * 4.2f))
            Mode.ACTING -> .34f
            Mode.WAITING_APPROVAL -> .12f
            Mode.ERROR -> .05f
        }
        smoothLevel += (energyTarget - smoothLevel) * .22f
        touchEnergy *= .86f
        wordPulse *= .78f
        val energy = (smoothLevel + touchEnergy * .30f + wordPulse * .25f).coerceIn(0f, 1f)

        val columns = 7
        val rows = 7
        val stepX = w / (columns + 1f)
        val stepY = h / (rows + 1f)
        val baseRadius = min(stepX, stepY) * .17f
        val silver = 0xFFE5E7EB.toInt()
        val softSilver = 0xFF9CA3AF.toInt()
        val warning = Theme.WARN
        val speed = when (mode) {
            Mode.IDLE -> .0f
            Mode.LISTENING -> 2.3f
            Mode.THINKING -> 1.7f
            Mode.SPEAKING -> 3.5f
            Mode.ACTING -> 2.6f
            Mode.WAITING_APPROVAL -> .4f
            Mode.ERROR -> .2f
        }

        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val nx = (column - (columns - 1) / 2f) / ((columns - 1) / 2f)
                val ny = (row - (rows - 1) / 2f) / ((rows - 1) / 2f)
                val distance = sqrt(nx * nx + ny * ny).coerceAtMost(1.42f)
                val centerWeight = (1f - distance / 1.42f).coerceIn(0f, 1f)
                val phase = time * speed + column * .57f + row * .39f + waveOffset
                val wave = sin(phase)
                val actionScan = if (mode == Mode.ACTING) {
                    (1f - abs(row - ((time * 3f).toInt() % rows)) / rows.toFloat()).coerceIn(0f, 1f)
                } else 0f
                val listeningPulse = if (mode == Mode.LISTENING) level * (1f - abs(column - 3) / 4f) else 0f
                val scale = (0.55f + centerWeight * .45f + abs(wave) * energy * .24f + actionScan * .22f + listeningPulse * .18f)
                    .coerceIn(.35f, 1.28f)
                val radius = baseRadius * scale
                val alpha = (65f + centerWeight * 150f + energy * 35f).coerceIn(45f, 255f).toInt()
                paint.color = if (mode == Mode.ERROR) warning
                    else if (mode == Mode.WAITING_APPROVAL && row == 0 && column == columns - 1) warning
                    else if (centerWeight > .60f || abs(wave) * energy > .25f) silver
                    else softSilver
                paint.alpha = alpha
                val x = stepX * (column + 1f)
                val y = stepY * (row + 1f)
                canvas.drawCircle(x, y, radius, paint)
            }
        }
        paint.alpha = 255

        val stillAnimating = mode != Mode.IDLE || smoothLevel > .02f || touchEnergy > .02f || wordPulse > .02f
        if (shouldAnimate() && stillAnimating) {
            postInvalidateDelayed(nextFrameDelayMs())
        }
    }
}
