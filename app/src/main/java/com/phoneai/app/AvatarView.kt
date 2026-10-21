package com.phoneai.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Orion's animated character. Two looks: 0 = Orion (robot head), 1 = Nova (friendly face).
 * It blinks, looks around, breathes, listens (ears glow with your voice), thinks (eyes drift up,
 * dots float), speaks (mouth moves with every word) and acts (eyes scan, ring orbits).
 */
class AvatarView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {

    @Volatile
    var mode: OrbView.Mode = OrbView.Mode.IDLE
        set(v) {
            field = v
            postInvalidateOnAnimation()
        }

    /** 0..1 microphone loudness while listening. */
    @Volatile
    var level: Float = 0f

    /** Small version (floating button, status bar): only eyes and mouth. */
    var compact: Boolean = false

    /** Dark disc behind the face (floating button). */
    var backing: Boolean = false

    var style: Int = Prefs.avatar(c)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()
    private val path = Path()

    private var mouth = 0f
    private var mouthTarget = 0f
    private var lastWord = 0L
    private var blinkUntil = 0L
    private var nextBlink = 0L
    private var smoothLevel = 0f

    /** Called for every spoken word; opens the mouth a bit. */
    fun pulseMouth() {
        mouthTarget = 0.45f + 0.55f * ((SystemClock.uptimeMillis() % 7L) / 6f)
        lastWord = SystemClock.uptimeMillis()
    }

    private fun accent(): Int = when (mode) {
        OrbView.Mode.IDLE -> Theme.CYAN
        OrbView.Mode.LISTENING -> Theme.OK
        OrbView.Mode.THINKING -> Theme.VIOLET
        OrbView.Mode.SPEAKING -> Theme.CYAN
        OrbView.Mode.ACTING -> Theme.WARN
    }

    private fun alpha(color: Int, a: Int): Int = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val t = (now % 1000000L) / 1000f
        val w = width.toFloat()
        val h = height.toFloat()
        val s = min(w, h)
        val col = accent()

        // ---- animation state
        if (now > nextBlink) {
            blinkUntil = now + 140
            nextBlink = now + 2200 + (now % 2500)
        }
        val blink = if (now < blinkUntil) 0.08f else 1f
        smoothLevel += ((if (mode == OrbView.Mode.LISTENING) level else 0f) - smoothLevel) * 0.25f
        if (mode == OrbView.Mode.SPEAKING) {
            if (now - lastWord > 450) {
                // engine gave no word timing: fall back to a talking rhythm
                mouthTarget = 0.35f + 0.65f * abs(sin(t * 9f) * sin(t * 2.3f + 1f))
            }
        } else {
            mouthTarget = 0f
        }
        mouth += (mouthTarget - mouth) * 0.35f
        if (mode == OrbView.Mode.SPEAKING && now - lastWord <= 450) mouthTarget *= 0.88f

        val bob = sin(t * 1.4f) * s * 0.012f
        val tilt = when (mode) {
            OrbView.Mode.LISTENING -> sin(t * 1.1f) * 2.5f
            OrbView.Mode.THINKING -> -4f
            else -> 0f
        }
        var gazeX = 0f
        var gazeY = 0f
        when (mode) {
            OrbView.Mode.IDLE -> {
                gazeX = sin(t * 0.6f) * 0.5f
                gazeY = sin(t * 0.43f) * 0.2f
            }
            OrbView.Mode.THINKING -> {
                gazeX = 0.55f + sin(t * 1.7f) * 0.2f
                gazeY = -0.7f
            }
            OrbView.Mode.ACTING -> {
                gazeX = sin(t * 3.2f)
                gazeY = 0.1f
            }
            OrbView.Mode.LISTENING -> gazeY = 0.05f
            OrbView.Mode.SPEAKING -> gazeX = sin(t * 0.9f) * 0.25f
        }

        val cx = w / 2f
        val cy = h / 2f + bob
        canvas.save()
        canvas.rotate(tilt, cx, cy)

        // ---- backing and glow
        if (backing) {
            fill.shader = null
            fill.color = 0xE6070B12.toInt()
            canvas.drawCircle(w / 2f, h / 2f, s * 0.5f, fill)
        }
        fill.shader = RadialGradient(
            cx, cy, s * 0.62f, intArrayOf(alpha(col, 70), alpha(col, 0)),
            floatArrayOf(0.45f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, s * 0.62f, fill)
        fill.shader = null

        when (style) {
            1 -> drawNova(canvas, cx, cy, s, col, blink, gazeX, gazeY, t)
            2 -> drawHuman(canvas, cx, cy, s, col, blink, gazeX, gazeY, t, true)
            3 -> drawHuman(canvas, cx, cy, s, col, blink, gazeX, gazeY, t, false)
            else -> drawRobot(canvas, cx, cy, s, col, blink, gazeX, gazeY, t)
        }

        canvas.restore()
        postInvalidateOnAnimation()
    }

    // ------------------------------------------------------------------ Orion: robot head

    private fun drawRobot(c: Canvas, cx: Float, cy: Float, s: Float, col: Int, blink: Float, gx: Float, gy: Float, t: Float) {
        val big = !compact
        val headW = if (big) 0.36f else 0.42f
        val headTop = cy - s * (if (big) 0.30f else 0.36f)
        val headBottom = cy + s * (if (big) 0.30f else 0.36f)

        if (big) {
            // antenna
            line.color = alpha(col, 200)
            line.strokeWidth = s * 0.012f
            c.drawLine(cx, headTop, cx, headTop - s * 0.10f, line)
            fill.color = col
            val ball = s * (0.026f + 0.012f * (0.5f + 0.5f * sin(t * 4f)))
            c.drawCircle(cx, headTop - s * 0.115f, ball, fill)

            // ears (glow with your voice while listening)
            val earGlow = if (mode == OrbView.Mode.LISTENING) 0.4f + smoothLevel * 0.6f else 0.25f
            fill.color = alpha(col, (earGlow * 255).toInt())
            rect.set(cx - s * (headW + 0.055f), cy - s * 0.07f, cx - s * (headW - 0.005f), cy + s * 0.09f)
            c.drawRoundRect(rect, s * 0.02f, s * 0.02f, fill)
            rect.set(cx + s * (headW - 0.005f), cy - s * 0.07f, cx + s * (headW + 0.055f), cy + s * 0.09f)
            c.drawRoundRect(rect, s * 0.02f, s * 0.02f, fill)
            if (mode == OrbView.Mode.LISTENING) {
                for (i in 0 until 2) {
                    val ph = ((t * 0.9f) + i * 0.5f) % 1f
                    line.color = alpha(Theme.OK, ((1f - ph) * 120).toInt())
                    line.strokeWidth = s * 0.01f
                    c.drawCircle(cx - s * headW, cy, s * (0.06f + 0.12f * ph), line)
                    c.drawCircle(cx + s * headW, cy, s * (0.06f + 0.12f * ph), line)
                }
            }
        }

        // head
        rect.set(cx - s * headW, headTop, cx + s * headW, headBottom)
        fill.shader = LinearGradient(0f, headTop, 0f, headBottom, 0xFF22324F.toInt(), 0xFF0E1522.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, s * 0.18f, s * 0.18f, fill)
        fill.shader = null
        line.color = alpha(col, 230)
        line.strokeWidth = s * (if (big) 0.014f else 0.03f)
        c.drawRoundRect(rect, s * 0.18f, s * 0.18f, line)

        // visor
        val visorTop = cy - s * (if (big) 0.19f else 0.22f)
        val visorBottom = cy + s * (if (big) 0.07f else 0.06f)
        rect.set(cx - s * (headW - 0.05f), visorTop, cx + s * (headW - 0.05f), visorBottom)
        fill.color = 0xFF050A12.toInt()
        c.drawRoundRect(rect, s * 0.1f, s * 0.1f, fill)

        // eyes
        val eyeY = (visorTop + visorBottom) / 2f
        val eyeDx = s * (if (big) 0.15f else 0.17f)
        val eyeW = s * (if (big) 0.085f else 0.1f)
        val eyeH = s * (if (big) 0.10f else 0.12f) * blink * (if (mode == OrbView.Mode.LISTENING) 1.12f else 1f)
        for (side in intArrayOf(-1, 1)) {
            val ex = cx + side * eyeDx + gx * s * 0.02f
            val ey = eyeY + gy * s * 0.02f
            fill.shader = RadialGradient(ex, ey, eyeW * 2.2f, intArrayOf(alpha(col, 120), alpha(col, 0)), floatArrayOf(0.2f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(ex, ey, eyeW * 2.2f, fill)
            fill.shader = null
            fill.color = col
            rect.set(ex - eyeW, ey - eyeH, ex + eyeW, ey + eyeH)
            c.drawRoundRect(rect, eyeW, eyeW, fill)
            if (blink > 0.5f) {
                fill.color = 0xFFFFFFFF.toInt()
                c.drawCircle(ex + gx * eyeW * 0.35f - eyeW * 0.2f, ey + gy * eyeH * 0.3f - eyeH * 0.2f, eyeW * 0.3f, fill)
            }
        }

        // mouth
        val my = cy + s * (if (big) 0.19f else 0.24f)
        val mw = s * (if (big) 0.13f else 0.15f)
        if (mouth < 0.08f) {
            line.color = alpha(col, 230)
            line.strokeWidth = s * (if (big) 0.014f else 0.03f)
            path.reset()
            path.moveTo(cx - mw, my)
            path.quadTo(cx, my + s * 0.035f, cx + mw, my)
            c.drawPath(path, line)
        } else {
            fill.color = col
            val mh = s * 0.012f + mouth * s * 0.075f
            rect.set(cx - mw * (0.7f + 0.3f * mouth), my - mh / 2f, cx + mw * (0.7f + 0.3f * mouth), my + mh / 2f)
            c.drawRoundRect(rect, mh / 2f, mh / 2f, fill)
        }

        if (big) drawStateExtras(c, cx, cy, s, headTop, col, t)
    }

    // ------------------------------------------------------------------ Nova: friendly face

    private fun drawNova(c: Canvas, cx: Float, cy: Float, s: Float, col: Int, blink: Float, gx: Float, gy: Float, t: Float) {
        val big = !compact
        val fw = s * 0.31f
        val top = cy - s * 0.33f
        val bottom = cy + s * 0.34f

        // hair back
        fill.color = 0xFF2A1F4D.toInt()
        rect.set(cx - fw * 1.12f, top - s * 0.04f, cx + fw * 1.12f, bottom - s * 0.02f)
        c.drawRoundRect(rect, fw, fw, fill)

        // face
        rect.set(cx - fw, top + s * 0.03f, cx + fw, bottom)
        fill.shader = LinearGradient(0f, top, 0f, bottom, 0xFFE9EEFF.toInt(), 0xFFB9C4FF.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, fw * 0.95f, fw * 0.95f, fill)
        fill.shader = null

        // fringe
        fill.color = 0xFF2A1F4D.toInt()
        path.reset()
        path.moveTo(cx - fw, top + s * 0.17f)
        path.quadTo(cx - fw * 0.9f, top - s * 0.02f, cx, top - s * 0.01f)
        path.quadTo(cx + fw * 0.9f, top - s * 0.02f, cx + fw, top + s * 0.17f)
        path.quadTo(cx + fw * 0.45f, top + s * 0.06f, cx + fw * 0.05f, top + s * 0.13f)
        path.quadTo(cx - fw * 0.4f, top + s * 0.05f, cx - fw, top + s * 0.17f)
        path.close()
        c.drawPath(path, fill)

        // glowing hair clip shows the state
        fill.color = col
        c.drawCircle(cx + fw * 0.75f, top + s * 0.1f, s * (0.02f + 0.008f * sin(t * 4f)), fill)

        val eyeY = cy - s * 0.04f
        val eyeDx = fw * 0.5f
        val browLift = when (mode) {
            OrbView.Mode.LISTENING -> 0.03f + smoothLevel * 0.02f
            OrbView.Mode.THINKING -> 0.025f
            OrbView.Mode.ACTING -> -0.01f
            else -> 0f
        }
        for (side in intArrayOf(-1, 1)) {
            val ex = cx + side * eyeDx
            // brow
            line.color = 0xFF2A1F4D.toInt()
            line.strokeWidth = s * 0.016f
            val lift = if (mode == OrbView.Mode.THINKING && side == 1) browLift * 1.8f else browLift
            path.reset()
            path.moveTo(ex - s * 0.07f, eyeY - s * (0.105f + lift))
            path.quadTo(ex, eyeY - s * (0.13f + lift), ex + s * 0.07f, eyeY - s * (0.105f + lift * 0.6f))
            c.drawPath(path, line)
            // eye white
            val ew = s * 0.07f
            val eh = s * 0.075f * blink
            fill.color = 0xFFFFFFFF.toInt()
            rect.set(ex - ew, eyeY - eh, ex + ew, eyeY + eh)
            c.drawOval(rect, fill)
            if (blink > 0.5f) {
                // iris + pupil follow the gaze
                val ix = ex + gx * ew * 0.35f
                val iy = eyeY + gy * eh * 0.35f
                fill.color = col
                c.drawCircle(ix, iy, ew * 0.62f, fill)
                fill.color = 0xFF10131F.toInt()
                c.drawCircle(ix, iy, ew * 0.3f, fill)
                fill.color = 0xFFFFFFFF.toInt()
                c.drawCircle(ix - ew * 0.2f, iy - ew * 0.2f, ew * 0.14f, fill)
            }
            // cheek
            if (big) {
                fill.color = 0x55FF8FB1
                c.drawCircle(ex + side * s * 0.02f, eyeY + s * 0.12f, s * 0.045f, fill)
            }
        }

        // mouth
        val my = cy + s * 0.18f
        val mw = s * 0.075f
        if (mouth < 0.08f) {
            line.color = 0xFF8A3B64.toInt()
            line.strokeWidth = s * 0.016f
            path.reset()
            path.moveTo(cx - mw, my)
            path.quadTo(cx, my + s * 0.04f, cx + mw, my)
            c.drawPath(path, line)
        } else {
            val mh = s * 0.01f + mouth * s * 0.08f
            fill.color = 0xFF5A1F44.toInt()
            rect.set(cx - mw, my - mh * 0.3f, cx + mw, my + mh)
            c.drawOval(rect, fill)
            fill.color = 0xFFFF8FB1.toInt()
            rect.set(cx - mw * 0.6f, my + mh * 0.45f, cx + mw * 0.6f, my + mh * 0.95f)
            c.drawOval(rect, fill)
        }

        if (big) drawStateExtras(c, cx, cy, s, top - s * 0.04f, col, t)
    }

    // ------------------------------------------------------------------ Ava and Kai: human faces

    private fun drawHuman(c: Canvas, cx: Float, cy: Float, s: Float, col: Int, blink: Float, gx: Float, gy: Float, t: Float, female: Boolean) {
        val big = !compact
        val skinA = if (female) 0xFFF1C09A.toInt() else 0xFFD9A073.toInt()
        val skinB = if (female) 0xFFD99B72.toInt() else 0xFFB87A50.toInt()
        val hair = if (female) 0xFF2A1812.toInt() else 0xFF1B1410.toInt()
        val fw = s * 0.25f
        val top = cy - s * 0.34f
        val chin = cy + s * 0.27f
        val eyeY = cy - s * 0.055f
        val eyeDx = fw * 0.47f

        if (big) {
            // shoulders and jacket
            fill.color = 0xFF18213A.toInt()
            path.reset()
            path.moveTo(cx - s * 0.5f, cy + s * 0.5f)
            path.cubicTo(cx - s * 0.5f, cy + s * 0.36f, cx - s * 0.3f, cy + s * 0.31f, cx - s * 0.12f, cy + s * 0.29f)
            path.lineTo(cx + s * 0.12f, cy + s * 0.29f)
            path.cubicTo(cx + s * 0.3f, cy + s * 0.31f, cx + s * 0.5f, cy + s * 0.36f, cx + s * 0.5f, cy + s * 0.5f)
            path.close()
            c.drawPath(path, fill)
        }
        if (female) {
            fill.color = hair
            rect.set(cx - fw * 1.22f, top - s * 0.03f, cx + fw * 1.22f, cy + s * 0.40f)
            c.drawRoundRect(rect, fw * 1.1f, fw * 1.1f, fill)
        }
        // neck
        fill.color = skinB
        rect.set(cx - fw * 0.32f, cy + s * 0.18f, cx + fw * 0.32f, cy + s * 0.34f)
        c.drawRect(rect, fill)
        if (big) {
            line.color = alpha(col, 230)
            line.strokeWidth = s * 0.012f
            path.reset()
            path.moveTo(cx - s * 0.16f, cy + s * 0.3f)
            path.lineTo(cx, cy + s * 0.42f)
            path.lineTo(cx + s * 0.16f, cy + s * 0.3f)
            c.drawPath(path, line)
        }
        // ears
        fill.color = skinB
        for (side in intArrayOf(-1, 1)) {
            rect.set(cx + side * fw - s * 0.03f, eyeY + s * 0.0f, cx + side * fw + s * 0.03f, eyeY + s * 0.1f)
            c.drawOval(rect, fill)
        }
        // face
        rect.set(cx - fw, top, cx + fw, chin)
        fill.shader = LinearGradient(0f, top, 0f, chin, skinA, skinB, Shader.TileMode.CLAMP)
        c.drawOval(rect, fill)
        fill.shader = null
        if (big) {
            fill.color = 0x2DFF6F6F
            c.drawCircle(cx - fw * 0.55f, cy + s * 0.075f, s * 0.05f, fill)
            c.drawCircle(cx + fw * 0.55f, cy + s * 0.075f, s * 0.05f, fill)
        }

        // hair in front
        fill.color = hair
        path.reset()
        if (female) {
            path.moveTo(cx - fw * 1.05f, eyeY + s * 0.05f)
            path.cubicTo(cx - fw * 1.15f, top - s * 0.02f, cx - fw * 0.4f, top - s * 0.09f, cx + fw * 0.1f, top - s * 0.05f)
            path.cubicTo(cx + fw * 0.7f, top - s * 0.07f, cx + fw * 1.15f, top, cx + fw * 1.05f, eyeY + s * 0.05f)
            path.cubicTo(cx + fw * 0.9f, top + s * 0.08f, cx + fw * 0.5f, top + s * 0.1f, cx, top + s * 0.075f)
            path.cubicTo(cx - fw * 0.5f, top + s * 0.14f, cx - fw * 0.85f, top + s * 0.16f, cx - fw * 1.05f, eyeY + s * 0.05f)
        } else {
            path.moveTo(cx - fw * 1.03f, eyeY - s * 0.03f)
            path.cubicTo(cx - fw * 1.1f, top - s * 0.05f, cx - fw * 0.3f, top - s * 0.08f, cx, top - s * 0.07f)
            path.cubicTo(cx + fw * 0.3f, top - s * 0.08f, cx + fw * 1.1f, top - s * 0.05f, cx + fw * 1.03f, eyeY - s * 0.03f)
            path.lineTo(cx + fw * 0.88f, eyeY - s * 0.1f)
            path.cubicTo(cx + fw * 0.5f, top + s * 0.05f, cx - fw * 0.5f, top + s * 0.05f, cx - fw * 0.88f, eyeY - s * 0.1f)
        }
        path.close()
        c.drawPath(path, fill)

        // eyes and brows
        val browLift = when (mode) {
            OrbView.Mode.LISTENING -> 0.025f + smoothLevel * 0.02f
            OrbView.Mode.THINKING -> 0.02f
            OrbView.Mode.ACTING -> -0.008f
            else -> 0f
        }
        for (side in intArrayOf(-1, 1)) {
            val ex = cx + side * eyeDx
            val ew = s * 0.058f
            val eh = s * 0.034f * blink
            fill.color = 0xFFFFFFFF.toInt()
            rect.set(ex - ew, eyeY - eh, ex + ew, eyeY + eh)
            c.drawOval(rect, fill)
            if (blink > 0.5f) {
                val ix = ex + gx * ew * 0.3f
                val iy = eyeY + gy * eh * 0.3f
                fill.color = if (female) 0xFF4A2E1B.toInt() else 0xFF3B2616.toInt()
                c.drawCircle(ix, iy, eh * 0.98f, fill)
                fill.color = 0xFF0B0B0F.toInt()
                c.drawCircle(ix, iy, eh * 0.45f, fill)
                fill.color = 0xFFFFFFFF.toInt()
                c.drawCircle(ix - eh * 0.3f, iy - eh * 0.3f, eh * 0.2f, fill)
            }
            // upper lid and lashes
            line.color = hair
            line.strokeWidth = s * 0.011f
            path.reset()
            path.moveTo(ex - ew * 1.05f, eyeY)
            path.quadTo(ex, eyeY - eh * 2.1f - s * 0.004f, ex + ew * 1.05f, eyeY)
            c.drawPath(path, line)
            if (female) {
                line.strokeWidth = s * 0.007f
                val ox = ex + side * ew * 1.0f
                c.drawLine(ox, eyeY - s * 0.004f, ox + side * s * 0.014f, eyeY - s * 0.016f, line)
                c.drawLine(ox - side * s * 0.012f, eyeY - s * 0.012f, ox + side * s * 0.004f, eyeY - s * 0.026f, line)
            }
            // brow
            line.color = hair
            line.strokeWidth = s * (if (female) 0.014f else 0.021f)
            val lift = if (mode == OrbView.Mode.THINKING && side == 1) browLift * 1.8f else browLift
            path.reset()
            path.moveTo(ex - ew * 1.1f, eyeY - s * (0.082f + lift * 0.5f))
            path.quadTo(ex, eyeY - s * (0.112f + lift), ex + ew * 1.1f, eyeY - s * (0.085f + lift * 0.6f))
            c.drawPath(path, line)
        }

        // nose
        line.color = 0x66803E22
        line.strokeWidth = s * 0.009f
        path.reset()
        path.moveTo(cx - s * 0.004f, eyeY + s * 0.035f)
        path.quadTo(cx - s * 0.02f, cy + s * 0.07f, cx - s * 0.03f, cy + s * 0.088f)
        path.quadTo(cx, cy + s * 0.104f, cx + s * 0.03f, cy + s * 0.088f)
        c.drawPath(path, line)

        // mouth
        val my = cy + s * 0.17f
        val mw = s * 0.07f
        val lip = if (female) 0xFFB5424B.toInt() else 0xFFA85A4E.toInt()
        if (mouth < 0.08f) {
            fill.color = lip
            path.reset()
            path.moveTo(cx - mw, my)
            path.quadTo(cx - mw * 0.4f, my - s * 0.017f, cx, my - s * 0.008f)
            path.quadTo(cx + mw * 0.4f, my - s * 0.017f, cx + mw, my)
            path.quadTo(cx, my + s * 0.034f, cx - mw, my)
            path.close()
            c.drawPath(path, fill)
            line.color = 0xFF6E2430.toInt()
            line.strokeWidth = s * 0.006f
            path.reset()
            path.moveTo(cx - mw, my)
            path.quadTo(cx, my + s * 0.012f, cx + mw, my)
            c.drawPath(path, line)
        } else {
            val mh = s * 0.012f + mouth * s * 0.07f
            fill.color = 0xFF4A1A1E.toInt()
            rect.set(cx - mw * 0.9f, my - mh * 0.25f, cx + mw * 0.9f, my + mh)
            c.drawOval(rect, fill)
            fill.color = 0xFFFFFFFF.toInt()
            rect.set(cx - mw * 0.7f, my - mh * 0.2f, cx + mw * 0.7f, my + mh * 0.22f)
            c.drawRoundRect(rect, s * 0.01f, s * 0.01f, fill)
            fill.color = 0xFFD9707A.toInt()
            rect.set(cx - mw * 0.45f, my + mh * 0.5f, cx + mw * 0.45f, my + mh * 0.95f)
            c.drawOval(rect, fill)
            line.color = lip
            line.strokeWidth = s * 0.014f
            path.reset()
            path.moveTo(cx - mw, my)
            path.quadTo(cx, my - mh * 0.55f - s * 0.012f, cx + mw, my)
            c.drawPath(path, line)
            path.reset()
            path.moveTo(cx - mw, my)
            path.quadTo(cx, my + mh * 1.4f, cx + mw, my)
            c.drawPath(path, line)
        }

        // glowing earpiece shows the state
        fill.color = col
        c.drawCircle(cx + fw * 1.02f, eyeY + s * 0.07f, s * (0.014f + 0.005f * sin(t * 4f)), fill)

        if (big) drawStateExtras(c, cx, cy, s, top - s * 0.05f, col, t)
    }

    // ------------------------------------------------------------------ state decorations

    private fun drawStateExtras(c: Canvas, cx: Float, cy: Float, s: Float, headTop: Float, col: Int, t: Float) {
        when (mode) {
            OrbView.Mode.THINKING -> {
                // three bouncing dots above the head
                for (i in 0 until 3) {
                    val k = sin(t * 5f - i * 0.8f)
                    fill.color = alpha(Theme.VIOLET, 230)
                    c.drawCircle(cx + (i - 1) * s * 0.07f, headTop - s * 0.14f - k * s * 0.012f, s * 0.022f, fill)
                }
            }
            OrbView.Mode.ACTING -> {
                val ang = t * 2.4f
                line.color = alpha(col, 160)
                line.strokeWidth = s * 0.012f
                rect.set(cx - s * 0.47f, cy - s * 0.47f, cx + s * 0.47f, cy + s * 0.47f)
                c.drawArc(rect, ang * 57.3f, 70f, false, line)
                c.drawArc(rect, ang * 57.3f + 180f, 70f, false, line)
                fill.color = 0xFFFFFFFF.toInt()
                c.drawCircle(cx + cos(ang) * s * 0.47f, cy + sin(ang) * s * 0.47f, s * 0.02f, fill)
            }
            OrbView.Mode.SPEAKING -> {
                for (i in 0 until 2) {
                    val ph = ((t * 0.8f) + i * 0.5f) % 1f
                    line.color = alpha(col, ((1f - ph) * 90).toInt())
                    line.strokeWidth = s * 0.01f
                    c.drawCircle(cx, cy, s * (0.40f + 0.12f * ph), line)
                }
            }
            else -> {}
        }
        // keep the unused helper referenced so a future skin can reuse it
        if (max(0f, 0f) > 1f) c.drawPoint(0f, 0f, fill)
    }
}
