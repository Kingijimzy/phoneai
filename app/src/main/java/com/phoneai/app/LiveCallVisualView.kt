package com.phoneai.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Lightweight 2D live-call renderer. Draws directly to Canvas; no WebView, 3D engine,
 * per-frame allocations, synthetic audio meter, or network assets.
 */
class LiveCallVisualView(context: Context) : View(context) {
    enum class Style { DOT_MATRIX, EYES }

    @Volatile var style: Style = Style.DOT_MATRIX
        set(value) { field = value; invalidate() }
    @Volatile var mode: OrbView.Mode = OrbView.Mode.IDLE
        set(value) { field = value; invalidate() }
    /** Real Android recognizer RMS level (0..1). */
    @Volatile var micLevel: Float = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }
    /** Real RMS amplitude of a generated Piper PCM block (0..1); zero for Android TTS. */
    @Volatile var speechAmplitude: Float = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }
    /** Event cue only, used for Android TTS word-range callbacks, never treated as amplitude. */
    @Volatile var wordPulse: Float = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }
    @Volatile var stepText: String = ""
        set(value) { field = value; invalidate() }

    var onVisualTap: (() -> Unit)? = null

    private val mint = 0xFF8CFFD0.toInt()
    private val dimMint = 0xFF285E4C.toInt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val mouth = Path()
    private val eyeRect = RectF()
    private var frame = 0
    private var lastWordAt = 0L
    private var lastMode = mode
    private var lastMic = 0f
    private var lastSpeech = 0f
    private val power by lazy { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
    private var reducedMotion = false
    private var frameDelay = 34L

    init {
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        isClickable = true
        contentDescription = "Live Orion visual. Tap to interrupt or speak."
        reducedMotion = try {
            (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) ||
                Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (_: Exception) { false }
    }

    fun pulseWord() { wordPulse = 1f; lastWordAt = System.currentTimeMillis(); invalidate() }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            performClick()
            onVisualTap?.invoke()
            return true
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); postInvalidateOnAnimation() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) invalidate()
    }

    private fun updatePowerPolicy() {
        val saver = try { power?.isPowerSaveMode == true } catch (_: Exception) { false }
        frameDelay = if (saver) 67L else 34L
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                if (power != null && power!!.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) frameDelay = 100L
            } catch (_: Exception) {}
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val now = System.currentTimeMillis()
        val t = if (reducedMotion) 0f else now / 1000f
        updatePowerPolicy()
        paint.color = android.graphics.Color.BLACK
        canvas.drawColor(android.graphics.Color.BLACK)
        if (style == Style.DOT_MATRIX) drawDots(canvas, w, h, t, now)
        else drawEyes(canvas, w, h, t, now)
        if (wordPulse > 0f) wordPulse = max(0f, wordPulse - if (reducedMotion) 1f else 0.11f)
        if (speechAmplitude > 0f && mode != OrbView.Mode.SPEAKING) speechAmplitude = 0f
        frame++
        if (isShown && windowVisibility == VISIBLE && !reducedMotion) postInvalidateDelayed(frameDelay)
    }

    private fun drawDots(canvas: Canvas, w: Float, h: Float, t: Float, now: Long) {
        val cols = 24
        val rows = 11
        val spacingX = min(w / 29f, h / 14f)
        val spacingY = spacingX
        val cx = w * .5f
        val cy = h * .48f
        val maxR = spacingX * .23f
        val base = when (mode) {
            OrbView.Mode.IDLE -> .16f
            OrbView.Mode.LISTENING -> .24f + micLevel * .76f
            OrbView.Mode.THINKING -> .72f
            OrbView.Mode.ACTING -> .63f
            OrbView.Mode.SPEAKING -> if (speechAmplitude > 0f) .22f + speechAmplitude * 1.35f else .16f + wordPulse * .9f
            OrbView.Mode.WAITING_APPROVAL -> .48f
        }.coerceIn(0f, 1f)
        val scanRow = if (reducedMotion) rows / 2 else ((t * 2.2f).toInt() % rows)
        val diag = if (reducedMotion) 0f else t * 3.4f
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val dx = col - (cols - 1) * .5f
                val dy = row - (rows - 1) * .5f
                val radial = kotlin.math.sqrt(dx * dx + dy * dy)
                val ripple = if (reducedMotion) .0f else abs(sin(radial * .85f - t * 2.1f))
                val columnVoice = when (mode) {
                    OrbView.Mode.LISTENING -> abs(sin(col * .58f)) * micLevel
                    OrbView.Mode.SPEAKING -> abs(sin(col * .51f)) * speechAmplitude + abs(sin(col * .51f)) * wordPulse * .48f
                    else -> 0f
                }
                val sweep = if (mode == OrbView.Mode.THINKING) (1f - abs(((col + row * 1.65f - diag).rem(cols + rows) / 4f) - .5f)).coerceIn(0f, 1f) else 0f
                val scan = if (mode == OrbView.Mode.ACTING) if (row == scanRow) .9f else .12f else 0f
                val energy = when (mode) {
                    OrbView.Mode.IDLE -> .13f + ripple * .16f
                    OrbView.Mode.LISTENING, OrbView.Mode.SPEAKING -> .12f + base * .35f + columnVoice * .55f
                    OrbView.Mode.THINKING -> .18f + sweep * .72f
                    OrbView.Mode.ACTING -> .16f + scan * .7f
                    OrbView.Mode.WAITING_APPROVAL -> .25f + (1f - min(1f, radial / 9f)) * .38f
                }.coerceIn(.08f, 1f)
                val pulse = if (mode == OrbView.Mode.IDLE && !reducedMotion) .75f + ripple * .35f else 1f
                val radius = max(1.05f, maxR * (.34f + energy * .85f) * pulse)
                paint.color = if (energy > .57f) mint else dimMint
                paint.alpha = (55 + energy * 200f).toInt().coerceIn(0, 255)
                canvas.drawCircle(cx + dx * spacingX, cy + dy * spacingY, radius, paint)
            }
        }
    }

    private fun drawEyes(canvas: Canvas, w: Float, h: Float, t: Float, now: Long) {
        val cx = w * .5f
        val cy = h * .46f
        val unit = min(w / 6.2f, h / 4.6f)
        val eyeW = unit * when (mode) {
            OrbView.Mode.LISTENING -> 1.65f
            OrbView.Mode.THINKING, OrbView.Mode.ACTING -> 1.42f
            else -> 1.5f
        }
        var eyeH = unit * .66f
        val blinkPhase = if (reducedMotion) 0f else (t % 4.1f)
        val blinking = mode != OrbView.Mode.WAITING_APPROVAL && blinkPhase > 3.86f
        if (blinking) eyeH *= max(.08f, (4.1f - blinkPhase) / .24f)
        if (mode == OrbView.Mode.THINKING || mode == OrbView.Mode.ACTING) eyeH *= .58f
        if (mode == OrbView.Mode.SPEAKING && speechAmplitude <= 0f) eyeH *= 1f + wordPulse * .12f
        val gazeX = when (mode) {
            OrbView.Mode.THINKING -> -.13f
            OrbView.Mode.ACTING -> (if (reducedMotion) 0f else sin(t * 3.2f) * .17f)
            else -> if (reducedMotion) 0f else sin(t * .7f) * .055f
        } * unit
        val gazeY = if (mode == OrbView.Mode.THINKING) -unit * .16f else if (mode == OrbView.Mode.WAITING_APPROVAL) unit * .03f else 0f
        for (side in -1..1 step 2) {
            val ex = cx + side * unit * 1.12f
            eyePaint.color = mint
            eyeRect.set(ex - eyeW / 2f, cy - eyeH / 2f, ex + eyeW / 2f, cy + eyeH / 2f)
            canvas.drawRoundRect(eyeRect, eyeH * .5f, eyeH * .5f, eyePaint)
            if (mode != OrbView.Mode.SPEAKING) {
                eyePaint.color = android.graphics.Color.BLACK
                val pupilR = max(2f, unit * .13f)
                canvas.drawCircle(ex + gazeX, cy + gazeY, pupilR, eyePaint)
            }
        }
        if (mode == OrbView.Mode.THINKING) {
            for (i in -1..1) {
                paint.color = mint
                paint.alpha = if (reducedMotion) 210 else (100 + abs(sin(t * 4f + i)) * 155).toInt()
                canvas.drawCircle(cx + i * unit * .34f, cy + unit * 1.0f, max(2f, unit * .075f), paint)
            }
        } else if (mode == OrbView.Mode.ACTING) {
            linePaint.color = mint
            linePaint.alpha = 200
            linePaint.strokeWidth = max(2f, unit * .035f)
            val scan = if (reducedMotion) .5f else .5f + sin(t * 2.8f) * .42f
            canvas.drawLine(cx - unit * 1.7f, cy + unit * 1.05f, cx - unit * 1.7f + unit * 3.4f * scan, cy + unit * 1.05f, linePaint)
        } else if (mode == OrbView.Mode.SPEAKING) {
            val mouthH = max(2f, unit * (.04f + speechAmplitude * .28f + wordPulse * .13f))
            linePaint.color = mint
            linePaint.alpha = 220
            linePaint.strokeWidth = max(2f, unit * .055f)
            mouth.reset()
            mouth.moveTo(cx - unit * .28f, cy + unit * .85f)
            mouth.quadTo(cx, cy + unit * (.85f + mouthH), cx + unit * .28f, cy + unit * .85f)
            canvas.drawPath(mouth, linePaint)
        } else if (mode == OrbView.Mode.WAITING_APPROVAL) {
            paint.color = mint
            paint.alpha = 170
            canvas.drawCircle(cx, cy + unit * 1.15f, max(2f, unit * .06f), paint)
        }
    }
}
