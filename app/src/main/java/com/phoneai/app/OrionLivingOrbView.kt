package com.phoneai.app

import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.animation.ValueAnimator
import android.view.View
import kotlin.math.min

/** Compact shared point-cloud identity used by chat and other Orion surfaces. */
class OrionLivingOrbView(context: Context) : View(context) {
    private val painter = OrionOrbPainter()
    @Volatile var mode: OrbView.Mode = OrbView.Mode.IDLE
        set(value) { field = value; invalidate() }
    @Volatile var micLevel: Float = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }
    @Volatile var speechAmplitude: Float = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }
    @Volatile var wordPulse: Float = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }

    private val power by lazy { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
    private val reducedMotion = try {
        (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) ||
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (_: Exception) { false }
    private var frameDelay = 34L

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        contentDescription = "Orion living silver orb"
        isFocusable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(android.graphics.Color.BLACK)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val saver = try { power?.isPowerSaveMode == true } catch (_: Exception) { false }
        frameDelay = if (saver) 67L else 34L
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                if ((power?.currentThermalStatus ?: 0) >= PowerManager.THERMAL_STATUS_SEVERE) frameDelay = 100L
            } catch (_: Exception) { }
        }
        val t = if (reducedMotion) 0f else android.os.SystemClock.elapsedRealtime() / 1000f
        painter.draw(
            canvas, w / 2f, h / 2f, min(w, h) * .43f, t,
            level = micLevel, mode = mode,
            speechAmplitude = speechAmplitude, wordPulse = wordPulse
        )
        if (wordPulse > 0f) wordPulse = (wordPulse - .12f).coerceAtLeast(0f)
        if (!reducedMotion && isShown && windowVisibility == VISIBLE) postInvalidateDelayed(frameDelay)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        invalidate()
    }
}
