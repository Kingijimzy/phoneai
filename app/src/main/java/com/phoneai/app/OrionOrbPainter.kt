package com.phoneai.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.sin

/**
 * Shared Orion identity renderer: a living silver point-cloud, not a static image.
 * Points are distributed over a Fibonacci sphere and deformed by travelling waves.
 * Voice energy is supplied by the real recognizer / generated PCM callbacks.
 */
class OrionOrbPainter {
    private val pointCount = 3000
    private val px = FloatArray(pointCount)
    private val py = FloatArray(pointCount)
    private val pz = FloatArray(pointCount)
    private val point = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    init {
        val golden = (PI * (3.0 - sqrt(5.0))).toFloat()
        for (i in 0 until pointCount) {
            val y = 1f - (i.toFloat() / (pointCount - 1)) * 2f
            val radial = sqrt(max(0f, 1f - y * y))
            val theta = golden * i
            px[i] = cos(theta) * radial
            py[i] = y
            pz[i] = sin(theta) * radial
        }
    }

    /** level is live mic RMS; speechAmplitude is generated PCM RMS; wordPulse is only a TTS timing cue. */
    fun draw(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        t: Float,
        level: Float = 0f,
        busy: Boolean = false,
        mode: OrbView.Mode = if (busy) OrbView.Mode.THINKING else OrbView.Mode.IDLE,
        speechAmplitude: Float = 0f,
        wordPulse: Float = 0f
    ) {
        if (radius <= 0f) return
        val alert = mode == OrbView.Mode.WAITING_APPROVAL || mode == OrbView.Mode.ERROR
        val voice = (if (mode == OrbView.Mode.SPEAKING) max(speechAmplitude, wordPulse * .48f) else level).coerceIn(0f, 1f)
        val thinking = mode == OrbView.Mode.THINKING || mode == OrbView.Mode.ACTING
        val listen = mode == OrbView.Mode.LISTENING
        val amplitude = when {
            mode == OrbView.Mode.SPEAKING -> .12f + voice * .16f
            listen -> .10f + voice * .12f
            thinking -> .13f
            else -> .07f
        }
        val frequency = when {
            thinking -> 3.0f
            listen -> 1.7f
            mode == OrbView.Mode.SPEAKING -> 1.5f
            else -> 1.1f
        }
        val rotation = t * when {
            thinking -> .55f
            listen -> .05f
            mode == OrbView.Mode.SPEAKING -> .12f
            else -> .08f
        }
        val cr = cos(rotation)
        val sr = sin(rotation)
        val tilt = .38f
        val ct = cos(tilt)
        val st = sin(tilt)
        val r = radius * (1f + .025f * sin(t * 1.6f) + voice * .06f)
        // Preserve the silver point-cloud identity while tinting each real runtime state.
        val stateColor = when (mode) {
            OrbView.Mode.IDLE -> 0xFFBFC5CC.toInt()
            OrbView.Mode.LISTENING -> 0xFF62CBE8.toInt()
            OrbView.Mode.THINKING -> 0xFF9B91F2.toInt()
            OrbView.Mode.SPEAKING -> 0xFF62D6C5.toInt()
            OrbView.Mode.ACTING -> 0xFFFFB86B.toInt()
            OrbView.Mode.WAITING_APPROVAL -> 0xFFFFC857.toInt()
            OrbView.Mode.ERROR -> 0xFFFF7777.toInt()
        }
        val glowColor = (stateColor and 0x00FFFFFF) or 0x33000000
        glow.shader = RadialGradient(cx, cy, r * 1.55f, intArrayOf(glowColor, 0x00000000), floatArrayOf(.08f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * 1.55f, glow)
        glow.shader = null

        // Full 3,000-point sphere at call size; scale down only for tiny chat indicators.
        val count = when {
            radius < 58f -> 700
            radius < 100f -> 1200
            radius < 165f -> 2100
            else -> pointCount
        }
        val pointScale = (radius / 190f).coerceIn(.34f, 1.45f)
        val wave = if (thinking) 1.2f else if (listen) .9f else if (mode == OrbView.Mode.SPEAKING) 1f else .35f

        for (i in 0 until count) {
            val x0 = px[i]
            val y0 = py[i]
            val z0 = pz[i]
            val d = sin(x0 * frequency * 2.2f + t * 1.1f) * cos(y0 * frequency * 1.9f - t * .8f) +
                sin(z0 * frequency * 2.6f + t * .7f + x0 * 1.3f) * .7f +
                sin((y0 * 3.1f + x0 * 2.3f) * frequency + t * wave * 1.6f) * .35f
            val travelling = sin(y0 * 7f - t * (2f + voice * 5f)) * voice * .07f
            val radial = 1f + d * (amplitude + voice * .15f) + travelling
            val x = x0 * radial
            val y = y0 * radial
            val z = z0 * radial
            val rx = x * cr + z * sr
            val rz = -x * sr + z * cr
            val ry = y * ct - rz * st
            val depthZ = y * st + rz * ct
            val perspective = 1f / (1.9f - depthZ * .55f)
            val sx = cx + rx * r * perspective * 1.9f
            val sy = cy + ry * r * perspective * 1.9f
            val depth = ((depthZ + 1f) * .5f).coerceIn(0f, 1f)
            val alpha = ((.12f + .88f * depth * depth) * if (alert) .95f else .82f).coerceIn(0f, 1f)
            val size = pointScale * (.48f + depth * 1.08f) * (1f + voice * .45f)
            val tint = stateColor
            val brightness = .78f + depth * .22f
            val rr = (android.graphics.Color.red(tint) * brightness).toInt().coerceIn(0, 255)
            val gg = (android.graphics.Color.green(tint) * brightness).toInt().coerceIn(0, 255)
            val bb = (android.graphics.Color.blue(tint) * brightness).toInt().coerceIn(0, 255)
            point.color = android.graphics.Color.argb((alpha * 255f).toInt(), rr, gg, bb)
            canvas.drawCircle(sx, sy, size, point)
        }
    }
}
