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
import android.os.Build
import android.os.PowerManager
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Orion's animated character. Looks: 0 = Orion, 1 = Nova, 2 = Atlas, 3 = Lumen.
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
    private var smoothTilt = 0f
    private var smoothGazeX = 0f
    private var smoothGazeY = 0f

    /** Called for every spoken word; opens the mouth a bit. */
    fun pulseMouth() {
        // Vary each syllable-like pulse so speech feels less like a fixed open/close loop.
        val now = SystemClock.uptimeMillis()
        mouthTarget = 0.28f + 0.68f * abs(sin(now * 0.017f))
        lastWord = now
        postInvalidateOnAnimation()
    }

    /** Adaptive frame pacing for entry-level phones under power or thermal pressure. */
    private fun nextFrameDelayMs(): Long {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return 33L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val thermal = power.currentThermalStatus
            if (thermal >= PowerManager.THERMAL_STATUS_SEVERE) return 120L
            if (thermal >= PowerManager.THERMAL_STATUS_MODERATE) return 66L
        }
        return if (power.isPowerSaveMode) 66L else 33L
    }

    private fun accent(): Int = when (mode) {
        OrbView.Mode.IDLE -> Theme.CYAN
        OrbView.Mode.LISTENING -> Theme.OK
        OrbView.Mode.THINKING -> Theme.VIOLET
        OrbView.Mode.SPEAKING -> Theme.CYAN
        OrbView.Mode.ACTING, OrbView.Mode.WAITING_APPROVAL -> Theme.WARN
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
        val targetTilt = when (mode) {
            OrbView.Mode.LISTENING -> sin(t * 1.1f) * 2.5f
            OrbView.Mode.THINKING -> -4f
            OrbView.Mode.WAITING_APPROVAL -> sin(t * 1.5f) * 1.2f
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
            OrbView.Mode.WAITING_APPROVAL -> gazeY = 0.2f
        }
        // Ease expression/gaze changes so state changes feel intentional instead of snapping.
        smoothTilt += (targetTilt - smoothTilt) * 0.18f
        smoothGazeX += (gazeX - smoothGazeX) * 0.16f
        smoothGazeY += (gazeY - smoothGazeY) * 0.16f
        val tilt = smoothTilt
        gazeX = smoothGazeX
        gazeY = smoothGazeY

        val cx = w / 2f
        val cy = h / 2f + bob
        canvas.save()
        canvas.rotate(tilt, cx, cy)

        // ---- backing and glow
        if (backing) {
            fill.shader = null
            fill.color = 0xE60B0B0B.toInt()
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
            2 -> drawAtlas(canvas, cx, cy, s, col, blink, gazeX, gazeY, t)
            3 -> drawLumen(canvas, cx, cy, s, col, blink, gazeX, gazeY, t)
            4 -> drawHuman(canvas, cx, cy, s, col, blink, gazeX, gazeY, t, true)
            5 -> drawHuman(canvas, cx, cy, s, col, blink, gazeX, gazeY, t, false)
            else -> drawRobot(canvas, cx, cy, s, col, blink, gazeX, gazeY, t)
        }

        canvas.restore()
        // Cap decorative animation at about 30 fps. This is smoother than a static face but
        // avoids driving a permanent 60 fps redraw loop on low-memory Android phones.
        if (isAttachedToWindow) postInvalidateDelayed(nextFrameDelayMs())
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
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
        fill.shader = LinearGradient(0f, headTop, 0f, headBottom, 0xFF313131.toInt(), 0xFF141414.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, s * 0.18f, s * 0.18f, fill)
        fill.shader = null
        line.color = alpha(col, 230)
        line.strokeWidth = s * (if (big) 0.014f else 0.03f)
        c.drawRoundRect(rect, s * 0.18f, s * 0.18f, line)

        // visor
        val visorTop = cy - s * (if (big) 0.19f else 0.22f)
        val visorBottom = cy + s * (if (big) 0.07f else 0.06f)
        rect.set(cx - s * (headW - 0.05f), visorTop, cx + s * (headW - 0.05f), visorBottom)
        fill.color = 0xFF0A0A0A.toInt()
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

        // Ears sit behind the jaw, with a soft warm inner shadow.
        fill.shader = LinearGradient(cx - fw, 0f, cx + fw, 0f, 0xFFD5A18E.toInt(), 0xFFB97F70.toInt(), Shader.TileMode.CLAMP)
        c.drawOval(cx - fw * 1.08f, cy - s * 0.045f, cx - fw * 0.82f, cy + s * 0.105f, fill)
        c.drawOval(cx + fw * 0.82f, cy - s * 0.045f, cx + fw * 1.08f, cy + s * 0.105f, fill)
        fill.shader = null

        // Tapered cheek-to-chin silhouette; warm multi-stop shading gives the face volume.
        path.reset()
        path.moveTo(cx - fw * 0.91f, top + s * 0.11f)
        path.quadTo(cx - fw * 0.98f, top + s * 0.01f, cx - fw * 0.58f, top + s * 0.025f)
        path.quadTo(cx, top - s * 0.005f, cx + fw * 0.58f, top + s * 0.025f)
        path.quadTo(cx + fw * 0.98f, top + s * 0.01f, cx + fw * 0.91f, top + s * 0.11f)
        path.lineTo(cx + fw * 0.82f, cy + s * 0.13f)
        path.quadTo(cx + fw * 0.64f, bottom - s * 0.015f, cx + fw * 0.28f, bottom - s * 0.005f)
        path.quadTo(cx, bottom + s * 0.015f, cx - fw * 0.28f, bottom - s * 0.005f)
        path.quadTo(cx - fw * 0.64f, bottom - s * 0.015f, cx - fw * 0.82f, cy + s * 0.13f)
        path.close()
        fill.shader = LinearGradient(cx - fw, top, cx + fw * 0.8f, bottom,
            intArrayOf(0xFFE8BBA7.toInt(), 0xFFF5D5C3.toInt(), 0xFFD99F8D.toInt()),
            floatArrayOf(0f, 0.48f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(path, fill)
        fill.shader = null
        // Subtle forehead highlight and jaw-side shadows make the face read less flat.
        fill.shader = RadialGradient(cx - fw * 0.28f, top + s * 0.20f, fw * 1.1f,
            intArrayOf(0x44FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        c.drawOval(cx - fw * 0.82f, top + s * 0.04f, cx + fw * 0.82f, bottom - s * 0.015f, fill)
        fill.shader = null
        fill.color = 0x1F7B3F39
        c.drawOval(cx - fw * 0.95f, cy - s * 0.015f, cx - fw * 0.57f, cy + s * 0.20f, fill)
        c.drawOval(cx + fw * 0.57f, cy - s * 0.015f, cx + fw * 0.95f, cy + s * 0.20f, fill)

        // Layered fringe with warm highlights instead of a single flat hair shape.
        path.reset()
        path.moveTo(cx - fw, top + s * 0.17f)
        path.quadTo(cx - fw * 0.9f, top - s * 0.02f, cx, top - s * 0.01f)
        path.quadTo(cx + fw * 0.9f, top - s * 0.02f, cx + fw, top + s * 0.17f)
        path.quadTo(cx + fw * 0.45f, top + s * 0.06f, cx + fw * 0.05f, top + s * 0.13f)
        path.quadTo(cx - fw * 0.4f, top + s * 0.05f, cx - fw, top + s * 0.17f)
        path.close()
        fill.shader = LinearGradient(cx - fw, top, cx + fw, top + s * 0.18f,
            intArrayOf(0xFF171326.toInt(), 0xFF46314D.toInt(), 0xFF241A32.toInt()),
            floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(path, fill)
        fill.shader = null
        line.color = 0x557E617D
        line.strokeWidth = s * 0.006f
        for (i in 0 until 5) {
            val x = cx - fw * 0.68f + i * fw * 0.32f
            path.reset()
            path.moveTo(x, top + s * 0.045f)
            path.quadTo(x + fw * 0.16f, top + s * 0.065f, x + fw * 0.20f, top + s * 0.14f)
            c.drawPath(path, line)
        }

        // glowing hair clip shows the state
        fill.color = col
        c.drawCircle(cx + fw * 0.75f, top + s * 0.1f, s * (0.02f + 0.008f * sin(t * 4f)), fill)

        val eyeY = cy - s * 0.04f
        val eyeDx = fw * 0.5f
        val browLift = when (mode) {
            OrbView.Mode.LISTENING -> 0.03f + smoothLevel * 0.02f
            OrbView.Mode.THINKING -> 0.025f
            OrbView.Mode.ACTING -> -0.01f
            OrbView.Mode.WAITING_APPROVAL -> 0.035f
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
                fill.color = 0xFF131313.toInt()
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

        // Nose bridge and soft nostril shadows add facial depth to Nova's human-style look.
        fill.color = 0x332A1F4D
        rect.set(cx - s * 0.018f, eyeY + s * 0.015f, cx + s * 0.018f, eyeY + s * 0.115f)
        c.drawRoundRect(rect, s * 0.018f, s * 0.018f, fill)
        fill.color = 0x558A6C8A
        c.drawOval(cx - s * 0.032f, eyeY + s * 0.09f, cx - s * 0.004f, eyeY + s * 0.115f, fill)
        c.drawOval(cx + s * 0.004f, eyeY + s * 0.09f, cx + s * 0.032f, eyeY + s * 0.115f, fill)
        line.color = 0x668A6C8A
        line.strokeWidth = s * 0.008f
        path.reset()
        path.moveTo(cx - s * 0.04f, eyeY + s * 0.12f)
        path.quadTo(cx, eyeY + s * 0.15f, cx + s * 0.04f, eyeY + s * 0.12f)
        c.drawPath(path, line)

        // Natural-toned upper/lower lip shading; mouth aperture follows the speech envelope.
        val my = cy + s * 0.18f
        val mw = s * 0.075f
        line.color = 0xFF9B5B59.toInt()
        line.strokeWidth = s * 0.009f
        path.reset()
        path.moveTo(cx - mw * 1.08f, my)
        path.quadTo(cx - mw * 0.48f, my - s * 0.022f, cx, my + s * 0.003f)
        path.quadTo(cx + mw * 0.48f, my - s * 0.022f, cx + mw * 1.08f, my)
        c.drawPath(path, line)
        if (mouth < 0.08f) {
            path.reset()
            path.moveTo(cx - mw, my + s * 0.008f)
            path.quadTo(cx, my + s * 0.042f, cx + mw, my + s * 0.008f)
            c.drawPath(path, line)
        } else {
            val mh = s * 0.008f + mouth * s * 0.065f
            fill.shader = LinearGradient(0f, my - mh * 0.4f, 0f, my + mh,
                0xFF5C2637.toInt(), 0xFF2D1722.toInt(), Shader.TileMode.CLAMP)
            rect.set(cx - mw * (0.75f + mouth * 0.12f), my - mh * 0.25f, cx + mw * (0.75f + mouth * 0.12f), my + mh)
            c.drawOval(rect, fill)
            fill.shader = null
            fill.color = 0xFFB76F76.toInt()
            rect.set(cx - mw * 0.55f, my + mh * 0.52f, cx + mw * 0.55f, my + mh * 0.84f)
            c.drawOval(rect, fill)
        }

        if (big) drawStateExtras(c, cx, cy, s, top - s * 0.04f, col, t)
    }

    // ------------------------------------------------------------------ state decorations

    // -------------------------------------------- Atlas: tactical visor
    private fun drawAtlas(c: Canvas, cx: Float, cy: Float, s: Float, col: Int, blink: Float, gx: Float, gy: Float, t: Float) {
        val w = s * 0.34f
        val top = cy - s * 0.31f
        val bottom = cy + s * 0.31f
        fill.color = 0xFF212121.toInt()
        path.reset()
        path.moveTo(cx - w, top + s * 0.10f)
        path.quadTo(cx - w * 1.05f, top - s * 0.02f, cx, top - s * 0.04f)
        path.quadTo(cx + w * 1.05f, top - s * 0.02f, cx + w, top + s * 0.10f)
        path.lineTo(cx + w * 0.92f, bottom - s * 0.08f)
        path.quadTo(cx, bottom + s * 0.12f, cx - w * 0.92f, bottom - s * 0.08f)
        path.close()
        c.drawPath(path, fill)
        line.color = alpha(col, 220)
        line.strokeWidth = s * 0.018f
        c.drawPath(path, line)
        rect.set(cx - w * 0.88f, cy - s * 0.105f, cx + w * 0.88f, cy + s * 0.065f)
        fill.color = alpha(col, 70)
        c.drawRoundRect(rect, s * 0.06f, s * 0.06f, fill)
        line.color = col
        line.strokeWidth = s * 0.014f
        c.drawRoundRect(rect, s * 0.06f, s * 0.06f, line)
        if (blink > 0.5f) {
            fill.color = 0xFFF0F0F0.toInt()
            c.drawCircle(cx - s * 0.13f + gx * s * 0.01f, cy - s * 0.02f + gy * s * 0.01f, s * 0.025f, fill)
            c.drawCircle(cx + s * 0.13f + gx * s * 0.01f, cy - s * 0.02f + gy * s * 0.01f, s * 0.025f, fill)
        }
        line.color = col
        line.strokeWidth = s * 0.016f
        val my = cy + s * 0.19f
        if (mouth < 0.08f) {
            c.drawLine(cx - s * 0.08f, my, cx + s * 0.08f, my, line)
        } else {
            fill.color = 0xFF0A0A0A.toInt()
            rect.set(cx - s * 0.07f, my - s * 0.015f, cx + s * 0.07f, my + mouth * s * 0.07f)
            c.drawOval(rect, fill)
        }
        fill.color = col
        c.drawCircle(cx - w * 0.98f, cy + s * 0.08f, s * 0.025f, fill)
        c.drawCircle(cx + w * 0.98f, cy + s * 0.08f, s * 0.025f, fill)
        if (!compact) drawStateExtras(c, cx, cy, s, top, col, t)
    }

    // -------------------------------------------- Lumen: soft luminous guide
    private fun drawLumen(c: Canvas, cx: Float, cy: Float, s: Float, col: Int, blink: Float, gx: Float, gy: Float, t: Float) {
        fill.color = alpha(col, 28)
        c.drawCircle(cx, cy, s * (0.37f + 0.012f * sin(t * 2f)), fill)
        line.color = alpha(col, 190)
        line.strokeWidth = s * 0.014f
        rect.set(cx - s * 0.30f, cy - s * 0.33f, cx + s * 0.30f, cy + s * 0.33f)
        c.drawOval(rect, line)
        fill.shader = LinearGradient(cx, cy - s * 0.28f, cx, cy + s * 0.29f, 0xFFF3FCFF.toInt(), 0xFFDFDFDF.toInt(), Shader.TileMode.CLAMP)
        rect.set(cx - s * 0.25f, cy - s * 0.28f, cx + s * 0.25f, cy + s * 0.29f)
        c.drawOval(rect, fill)
        fill.shader = null
        val eyeY = cy - s * 0.045f
        for (side in intArrayOf(-1, 1)) {
            val ex = cx + side * s * 0.105f
            fill.color = 0xFF404040.toInt()
            if (blink > 0.5f) {
                c.drawCircle(ex + gx * s * 0.012f, eyeY + gy * s * 0.01f, s * 0.028f, fill)
            } else {
                line.color = 0xFF404040.toInt()
                line.strokeWidth = s * 0.012f
                c.drawLine(ex - s * 0.025f, eyeY, ex + s * 0.025f, eyeY, line)
            }
        }
        val my = cy + s * 0.12f
        line.color = 0xFF7C7C7C.toInt()
        line.strokeWidth = s * 0.014f
        if (mouth < 0.08f) {
            path.reset()
            path.moveTo(cx - s * 0.06f, my)
            path.quadTo(cx, my + s * 0.045f, cx + s * 0.06f, my)
            c.drawPath(path, line)
        } else {
            fill.color = 0xFF7C7C7C.toInt()
            rect.set(cx - s * 0.055f, my - s * 0.01f, cx + s * 0.055f, my + mouth * s * 0.055f)
            c.drawOval(rect, fill)
        }
        fill.color = col
        val orbit = t * 0.7f
        c.drawCircle(cx + cos(orbit) * s * 0.36f, cy + sin(orbit) * s * 0.36f, s * 0.022f, fill)
        c.drawCircle(cx + cos(orbit + 2.4f) * s * 0.36f, cy + sin(orbit + 2.4f) * s * 0.36f, s * 0.014f, fill)
    }

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
            OrbView.Mode.WAITING_APPROVAL -> {
                line.color = alpha(Theme.WARN, 230)
                line.strokeWidth = s * 0.018f
                rect.set(cx - s * 0.43f, cy - s * 0.43f, cx + s * 0.43f, cy + s * 0.43f)
                c.drawArc(rect, -70f, 140f, false, line)
                c.drawArc(rect, 110f, 140f, false, line)
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
