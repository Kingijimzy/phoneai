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
        val r = min(w, h) * .365f
        if (r < 2f) return

        val time = SystemClock.uptimeMillis() / 1000f
        val energyTarget = when (mode) {
            Mode.IDLE -> .12f
            Mode.LISTENING -> level.coerceIn(0f, 1f)
            Mode.THINKING -> .58f
            Mode.SPEAKING -> .46f + .32f * (.5f + .5f * sin(time * 4.2f))
            Mode.ACTING -> .42f
            Mode.WAITING_APPROVAL -> .18f
        }
        smoothLevel += (energyTarget - smoothLevel) * .14f
        touchEnergy *= .94f
        val energy = (smoothLevel + touchEnergy * .35f).coerceIn(0f, 1.2f)
        val speed = when (mode) {
            Mode.IDLE -> .30f
            Mode.LISTENING -> .72f + energy
            Mode.THINKING -> 1.10f
            Mode.SPEAKING -> 1.30f
            Mode.ACTING -> .82f
            Mode.WAITING_APPROVAL -> .42f
        }
        val pulse = 1f + .012f * sin(time * speed * 1.7f) + energy * .012f
        val radius = r * pulse
        val silver = 0xFFE9ECEF.toInt()
        val softSilver = 0xFFB8BEC4.toInt()
        val dimSilver = 0xFF737A80.toInt()

        // Broad, soft corona around the core; it breathes with voice state but remains monochrome.
        glowPaint.shader = RadialGradient(cx, cy, radius * 1.7f,
            intArrayOf(
                0x24FFFFFF,
                ((25 + (energy * 36f).toInt()).coerceAtMost(64) shl 24) or 0x00FFFFFF,
                0x00000000
            ),
            floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, radius * 1.7f, glowPaint)
        glowPaint.shader = null

        // Three slowly orbiting hairline ellipses make the core feel engineered rather than like a blob.
        ring.strokeWidth = max(1f, radius * .009f)
        ring.color = 0x507E858B
        rect.set(cx - radius * 1.33f, cy - radius * .53f, cx + radius * 1.33f, cy + radius * .53f)
        canvas.save()
        canvas.rotate(-24f + sin(time * .17f) * 5f, cx, cy)
        canvas.drawOval(rect, ring)
        canvas.restore()
        ring.color = 0x427E858B
        rect.set(cx - radius * 1.23f, cy - radius * .37f, cx + radius * 1.23f, cy + radius * .37f)
        canvas.save()
        canvas.rotate(37f - sin(time * .13f) * 7f, cx, cy)
        canvas.drawOval(rect, ring)
        canvas.restore()

        // Obsidian glass sphere with a real radial highlight and a crisp silver rim.
        paint.shader = RadialGradient(cx - radius * .31f, cy - radius * .39f, radius * 1.45f,
            intArrayOf(0xFF555B61.toInt(), 0xFF262A2E.toInt(), 0xFF090A0B.toInt()),
            floatArrayOf(0f, .48f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, radius, paint)
        paint.shader = null

        clip.reset()
        clip.addCircle(cx, cy, radius * .982f, Path.Direction.CW)
        val saved = canvas.save()
        canvas.clipPath(clip)

        // A fine silver energy ribbon sweeps through the sphere with independent flowing strands.
        val baseY = cy + sin(time * speed * .56f) * radius * .08f
        val phases = floatArrayOf(0f, 1.45f, 3.0f, 4.6f)
        val amps = floatArrayOf(.11f, .075f, .048f, .026f)
        val alphas = intArrayOf(225, 130, 92, 66)
        for (layer in 0..3) {
            wavePath.reset()
            val points = 60
            for (i in 0..points) {
                val nx = i.toFloat() / points
                val x = cx - radius + radius * 2f * nx
                val envelope = sin(PI.toFloat() * nx).coerceAtLeast(0f).let { .20f + .80f * it }
                val wave = sin(nx * 2f * PI.toFloat() + time * speed + phases[layer]) * radius * (amps[layer] + energy * .035f) * envelope
                val undertow = sin(nx * 4f * PI.toFloat() - time * speed * .48f + phases[layer]) * radius * .018f
                val y = baseY + wave + undertow
                if (i == 0) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
            }
            wavePaint.color = (silver and 0x00FFFFFF) or (alphas[layer] shl 24)
            wavePaint.strokeWidth = max(1.1f, radius * if (layer == 0) .018f else .009f)
            canvas.drawPath(wavePath, wavePaint)
        }

        // A travelling specular bead adds a live, liquid-metal highlight without heavy 3D rendering.
        val beadX = cx + sin(time * speed * .7f) * radius * .72f
        val beadY = baseY + sin(time * speed + .6f) * radius * .10f
        glowPaint.shader = RadialGradient(beadX, beadY, radius * .13f,
            intArrayOf(0xFFFFFFFF.toInt(), 0x88E9ECEF.toInt(), 0x00000000),
            floatArrayOf(0f, .38f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(beadX, beadY, radius * .13f, glowPaint)
        glowPaint.shader = null

        // Fine curved meridians and a subtle hotspot establish a classic polished-sphere silhouette.
        ring.strokeWidth = max(1f, radius * .008f)
        ring.color = 0x458F969D
        rect.set(cx - radius * .64f, cy - radius, cx + radius * .64f, cy + radius)
        canvas.save()
        canvas.rotate(-18f + sin(time * .12f) * 4f, cx, cy)
        canvas.drawOval(rect, ring)
        canvas.restore()
        rect.set(cx - radius, cy - radius * .50f, cx + radius, cy + radius * .50f)
        canvas.drawOval(rect, ring)

        paint.shader = RadialGradient(cx - radius * .28f, cy - radius * .53f, radius * .56f,
            intArrayOf(0x48FFFFFF, 0x12FFFFFF, 0x00000000),
            floatArrayOf(0f, .48f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, radius, paint)
        paint.shader = null
        canvas.restoreToCount(saved)

        // Thin rim and a moving light arc keep the silhouette readable on a black screen.
        ring.strokeWidth = max(1.2f, radius * .012f)
        ring.color = 0xA6B7BEC5.toInt()
        canvas.drawCircle(cx, cy, radius * .985f, ring)
        ring.strokeWidth = max(1f, radius * .017f)
        ring.color = (silver and 0x00FFFFFF) or 0xB0000000.toInt()
        rect.set(cx - radius * 1.02f, cy - radius * 1.02f, cx + radius * 1.02f, cy + radius * 1.02f)
        canvas.drawArc(rect, (time * 27f) % 360f, 50f + energy * 26f, false, ring)

        // Two moving pin-lights sit on the orbital paths.
        for (i in 0..1) {
            val angle = time * speed * (if (i == 0) 0.56f else -0.38f) + i * PI.toFloat()
            val x = cx + cos(angle) * radius * 1.19f
            val y = cy + sin(angle) * radius * (if (i == 0) .47f else .36f)
            glowPaint.shader = RadialGradient(x, y, radius * .12f,
                intArrayOf(0xFFFFFFFF.toInt(), 0x99D7DCE0.toInt(), 0x00000000),
                floatArrayOf(0f, .36f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(x, y, radius * .12f, glowPaint)
            glowPaint.shader = null
        }

        paint.shader = null
        glowPaint.shader = null
        wavePaint.shader = null
        fluid.shader = null
        if (isShown && windowVisibility == VISIBLE) postInvalidateDelayed(nextFrameDelayMs())
    }
}
}
