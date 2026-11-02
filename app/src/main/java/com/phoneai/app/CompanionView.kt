package com.phoneai.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The desk companion. Three looks (orb, fox, robot) that breathe, blink, glance around, follow your finger with
 * their eyes, think while a reply loads, listen while you talk, talk with their mouth, and react to wins, losses and alerts.
 * Animation stops when the system animation scale is off. Tap to switch look.
 */
internal class CompanionView(context: Context) : View(context) {
    enum class Activity { IDLE, THINKING, LISTENING, SPEAKING }

    var character = 0
        set(v) { field = ((v % 3) + 3) % 3; invalidate() }
    var onCharacterChanged: ((Int) -> Unit)? = null
    var activity = Activity.IDLE
        set(v) { field = v; invalidate() }
    /** Kept for callers that only know about speaking. */
    var speaking: Boolean
        get() = activity == Activity.SPEAKING
        set(v) { activity = if (v) Activity.SPEAKING else Activity.IDLE }

    private var mood = CompanionMood.Mood.CALM
    private var phase = 0f
    private var hop = 0f
    private var reaction = CompanionLife.Reaction.NONE
    private var rx = 0f
    private var lookX = 0f
    private var lookY = 0f
    private var lookAtTime = 0L
    private var animator: ValueAnimator? = null
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()
    private val path = Path()
    private val loc = IntArray(2)

    init {
        isClickable = true
        contentDescription = "Desk companion"
        setOnClickListener { character = character + 1; onCharacterChanged?.invoke(character); hopOnce() }
    }

    fun setMood(m: CompanionMood.Mood) {
        if (m == mood) return
        mood = m; hopOnce(); invalidate()
    }

    /** Where the person last touched the screen (raw screen coordinates). The eyes follow it for a few seconds. */
    fun lookAt(rawX: Float, rawY: Float) {
        getLocationOnScreen(loc)
        val dx = rawX - (loc[0] + width / 2f); val dy = rawY - (loc[1] + height / 2f)
        val d = hypot(dx, dy).coerceAtLeast(1f)
        val k = minOf(1f, d / (width * 2f))
        lookX = dx / d * k; lookY = dy / d * k; lookAtTime = SystemClock.uptimeMillis()
    }

    fun react(r: CompanionLife.Reaction) {
        if (r == CompanionLife.Reaction.NONE || !Motion.enabled(context)) return
        reaction = r
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (r == CompanionLife.Reaction.ALARM) 1400L else 1000L
            addUpdateListener { rx = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { reaction = CompanionLife.Reaction.NONE; rx = 0f; invalidate() }
            })
            start()
        }
    }

    private fun hopOnce() {
        if (!Motion.enabled(context)) return
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450L
            addUpdateListener { hop = sin(it.animatedFraction * PI).toFloat(); invalidate() }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!Motion.enabled(context)) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 6000L; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel(); animator = null
        super.onDetachedFromWindow()
    }

    private fun colour(): Int = when (mood) {
        CompanionMood.Mood.CALM -> Theme.CYAN
        CompanionMood.Mood.HAPPY -> Color.rgb(80, 200, 120)
        CompanionMood.Mood.EXCITED -> Color.rgb(255, 196, 61)
        CompanionMood.Mood.WORRIED -> Color.rgb(255, 140, 66)
        CompanionMood.Mood.ALERT -> Color.rgb(255, 82, 82)
    }

    override fun onMeasure(w: Int, h: Int) {
        val s = (88 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(resolveSize(s, w), resolveSize(s, h))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val tau = 2f * PI.toFloat()
        val breathe = 1f + 0.04f * sin(phase * tau * 2f)
        val shake = when (reaction) {
            CompanionLife.Reaction.FLINCH -> sin(rx * tau * 4f) * (1f - rx) * w * 0.05f
            CompanionLife.Reaction.ALARM -> sin(rx * tau * 6f) * (1f - rx) * w * 0.035f
            else -> 0f
        }
        val bounce = if (reaction == CompanionLife.Reaction.CELEBRATE) sin(rx * PI.toFloat() * 3f).coerceAtLeast(0f) * h * 0.1f else 0f
        val sway = sin(phase * tau) * w * 0.012f
        val cx = w / 2f + shake + sway; val cy = h / 2f - hop * h * 0.12f - bounce
        val r = minOf(w, h) * 0.32f * breathe
        val col = colour()
        val live = Motion.enabled(context)

        // glow, and a pulsing ring while listening or alarmed
        fill.style = Paint.Style.FILL; fill.color = Color.argb(46, Color.red(col), Color.green(col), Color.blue(col))
        canvas.drawCircle(cx, cy, r * 1.3f, fill)
        if (activity == Activity.LISTENING || reaction == CompanionLife.Reaction.ALARM) {
            val p = (phase * 6f) % 1f
            line.color = Color.argb(((1f - p) * 160).toInt(), Color.red(col), Color.green(col), Color.blue(col)); line.strokeWidth = r * 0.07f
            canvas.drawCircle(cx, cy, r * (1.1f + p * 0.55f), line)
        }
        fill.color = col
        when (character) {
            1 -> {
                path.reset()
                path.moveTo(cx - r * 0.95f, cy - r * 0.2f); path.lineTo(cx - r * 0.75f, cy - r * 1.35f); path.lineTo(cx - r * 0.1f, cy - r * 0.85f); path.close()
                path.moveTo(cx + r * 0.95f, cy - r * 0.2f); path.lineTo(cx + r * 0.75f, cy - r * 1.35f); path.lineTo(cx + r * 0.1f, cy - r * 0.85f); path.close()
                canvas.drawPath(path, fill); canvas.drawCircle(cx, cy, r, fill)
            }
            2 -> {
                rect.set(cx - r, cy - r * 0.9f, cx + r, cy + r * 0.9f)
                canvas.drawRoundRect(rect, r * 0.3f, r * 0.3f, fill)
                line.color = col; line.strokeWidth = r * 0.08f
                canvas.drawLine(cx, cy - r * 0.9f, cx, cy - r * 1.25f, line)
                val tip = if (activity == Activity.THINKING) 0.15f + 0.1f * sin(phase * tau * 12f) else 0.1f
                canvas.drawCircle(cx, cy - r * 1.3f, r * tip, fill)
            }
            else -> canvas.drawCircle(cx, cy, r, fill)
        }

        // eyes: follow the finger for a few seconds, otherwise glance about
        val ink = Color.rgb(16, 20, 28)
        val following = live && SystemClock.uptimeMillis() - lookAtTime < 3500L
        var px = if (following) lookX else sin(phase * tau * 1.5f) * 0.5f
        var py = if (following) lookY else cos(phase * tau * 0.8f) * 0.25f
        if (activity == Activity.THINKING) { px = 0.6f; py = -0.7f }
        val ex = r * 0.38f; val ey = cy - r * 0.15f; val er = r * 0.17f
        val blink = live && ((phase * 7f) % 1f) > 0.965f
        val squint = reaction == CompanionLife.Reaction.FLINCH
        val worried = mood == CompanionMood.Mood.WORRIED || mood == CompanionMood.Mood.ALERT
        val alarm = reaction == CompanionLife.Reaction.ALARM
        for (side in intArrayOf(-1, 1)) {
            val ox = cx + side * ex
            fill.color = Color.WHITE
            rect.set(ox - er, ey - (if (blink || squint) er * 0.15f else er * (if (alarm) 1.35f else 1f)), ox + er, ey + (if (blink || squint) er * 0.15f else er * (if (alarm) 1.35f else 1f)))
            canvas.drawOval(rect, fill)
            if (!blink && !squint) {
                fill.color = ink
                canvas.drawCircle(ox + px * er * 0.55f, ey + py * er * 0.55f, er * 0.55f, fill)
            }
            if (worried) { line.color = ink; line.strokeWidth = r * 0.06f; canvas.drawLine(ox - er, ey - er * 1.5f - side * er * 0.3f, ox + er, ey - er * 1.5f + side * er * 0.3f, line) }
        }

        // mouth
        fill.color = ink; line.color = ink; line.strokeWidth = r * 0.07f
        val my = cy + r * 0.45f
        if (activity == Activity.SPEAKING) {
            val open = r * (0.08f + 0.16f * (0.5f + 0.5f * sin(phase * tau * 18f)))
            rect.set(cx - r * 0.2f, my - open, cx + r * 0.2f, my + open); canvas.drawOval(rect, fill)
        } else if (reaction == CompanionLife.Reaction.CELEBRATE) {
            rect.set(cx - r * 0.34f, my - r * 0.25f, cx + r * 0.34f, my + r * 0.2f); canvas.drawArc(rect, 0f, 180f, true, fill)
        } else if (alarm) {
            rect.set(cx - r * 0.12f, my - r * 0.1f, cx + r * 0.12f, my + r * 0.18f); canvas.drawOval(rect, fill)
        } else {
            rect.set(cx - r * 0.3f, my - r * 0.2f, cx + r * 0.3f, my + r * 0.2f)
            when {
                worried || squint -> canvas.drawArc(rect, 200f, 140f, false, line)
                mood == CompanionMood.Mood.CALM -> canvas.drawLine(cx - r * 0.2f, my, cx + r * 0.2f, my, line)
                else -> canvas.drawArc(rect, 20f, 140f, false, line)
            }
        }

        // thinking dots
        if (activity == Activity.THINKING) {
            fill.color = col
            for (i in 0..2) {
                val t = (phase * 5f + i * 0.33f) % 1f
                canvas.drawCircle(cx + (i - 1) * r * 0.35f, cy - r * 1.55f - sin(t * PI.toFloat()) * r * 0.2f, r * 0.09f, fill)
            }
        }
        // sparkles when celebrating
        if (reaction == CompanionLife.Reaction.CELEBRATE) {
            fill.color = Color.rgb(255, 214, 90)
            for (i in 0 until 8) {
                val a = i / 8f * tau + rx * 1.5f; val d = r * (1.2f + rx * 0.7f)
                canvas.drawCircle(cx + cos(a) * d, cy + sin(a) * d, r * 0.07f * (1f - rx * 0.6f), fill)
            }
        }
    }
}
