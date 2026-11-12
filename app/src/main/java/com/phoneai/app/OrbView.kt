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

    @Volatile
    var speechAmplitude: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            if (mode == Mode.SPEAKING) postInvalidateOnAnimation()
        }

    var backing: Boolean = false

    private val painter = OrionOrbPainter()
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
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val powerSave = power?.isPowerSaveMode ?: false
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
        } else 0
        return OrionPerformancePolicy.orbFrameDelayMs(powerSave, thermal)
    }

    private fun shouldAnimate(): Boolean =
        isAttachedToWindow && isShown && visibility == VISIBLE && windowVisibility == VISIBLE

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        animate().cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) postInvalidateOnAnimation()
        else Unit
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
        touchEnergy *= .86f
        wordPulse *= .78f
        val visualLevel = (if (mode == Mode.SPEAKING) max(speechAmplitude, wordPulse * .48f) else level)
            .plus(touchEnergy * .22f).coerceIn(0f, 1f)
        canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        painter.draw(
            canvas, w / 2f, h / 2f, min(w, h) * .43f, time,
            level = visualLevel,
            mode = mode,
            speechAmplitude = speechAmplitude,
            wordPulse = wordPulse
        )

        val stillAnimating = mode != Mode.IDLE || touchEnergy > .02f || wordPulse > .02f || speechAmplitude > .01f
        if (shouldAnimate() && stillAnimating) postInvalidateDelayed(nextFrameDelayMs())
    }
}
