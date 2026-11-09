package com.phoneai.app

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import kotlin.math.PI
import kotlin.math.sin

/**
 * Orion's glass orb: a deep blue/indigo sphere with a turning glass swirl, a bright rim, a soft highlight and two
 * glowing eyes that blink and widen with the voice level. One painter is shared by the chat home screen
 * (Compose) and the live-call screen (View) so both show the same face.
 */
class OrionOrbPainter {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val eye = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    /** [t] seconds, [level] 0..1 voice energy, [busy] true while thinking or acting (eyes narrow, swirl speeds up). */
    fun draw(canvas: Canvas, cx: Float, cy: Float, radius: Float, t: Float, level: Float = 0f, busy: Boolean = false) {
        val breathe = 1f + 0.025f * sin(t * 1.6f) + level * 0.06f
        val r = radius * breathe
        val save = canvas.save()
        // soft outer glow
        glow.shader = RadialGradient(cx, cy, r * 1.45f, intArrayOf(0x553B5BFF, 0x00000000), floatArrayOf(.55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * 1.45f, glow)
        // body
        fill.shader = RadialGradient(cx - r * .28f, cy - r * .3f, r * 1.25f,
            intArrayOf(0xFF8CC0FF.toInt(), 0xFF3A56E8.toInt(), 0xFF1B1F7A.toInt(), 0xFF050824.toInt()),
            floatArrayOf(0f, .38f, .75f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, fill)
        // turning glass swirl, clipped to the sphere
        val spin = t * (if (busy) 70f else 22f)
        canvas.save()
        val clip = android.graphics.Path().apply { addCircle(cx, cy, r, android.graphics.Path.Direction.CW) }
        canvas.clipPath(clip)
        canvas.rotate(spin, cx, cy)
        fill.shader = SweepGradient(cx, cy,
            intArrayOf(0x00FFFFFF, 0x66A6D8FF, 0x00FFFFFF, 0x4491A6FF, 0x00FFFFFF, 0x55E8F5C0, 0x00FFFFFF),
            floatArrayOf(0f, .16f, .33f, .5f, .66f, .82f, 1f))
        rect.set(cx - r * 1.05f, cy - r * .62f, cx + r * 1.05f, cy + r * .62f)
        canvas.drawOval(rect, fill)
        canvas.restore()
        // bright rim
        ring.strokeWidth = r * .045f
        ring.shader = SweepGradient(cx, cy, intArrayOf(0xAAFFFFFF.toInt(), 0x445B8CFF, 0xAA7DF0E0.toInt(), 0x44FFF3A6, 0xAAFFFFFF.toInt()), null)
        canvas.drawCircle(cx, cy, r * .98f, ring)
        // specular highlight
        fill.shader = LinearGradient(cx - r * .6f, cy - r * .75f, cx - r * .1f, cy - r * .2f, 0x88FFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
        rect.set(cx - r * .62f, cy - r * .78f, cx - r * .05f, cy - r * .28f)
        canvas.drawOval(rect, fill)
        fill.shader = null
        // eyes
        val blink = (t % 4.2f).let { if (it > 4.05f) 0.12f else 1f }
        var eyeH = r * (.30f + level * .12f) * blink
        if (busy) eyeH *= .62f
        val eyeW = r * .105f
        val gap = r * .30f
        glow.shader = null
        glow.color = 0x66FFFFFF
        glow.maskFilter = android.graphics.BlurMaskFilter(r * .07f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        for (dx in floatArrayOf(-gap, gap)) {
            rect.set(cx + dx - eyeW, cy - eyeH * .5f - r * .02f, cx + dx + eyeW, cy + eyeH * .5f - r * .02f)
            canvas.drawRoundRect(rect, eyeW, eyeW, glow)
            canvas.drawRoundRect(rect, eyeW, eyeW, eye)
        }
        glow.maskFilter = null
        canvas.restoreToCount(save)
    }

    @Suppress("unused") private val tau = (2 * PI).toFloat()
}
