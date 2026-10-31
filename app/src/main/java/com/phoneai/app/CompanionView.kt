package com.phoneai.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.sin

/**
 * The desk companion: a small animated character that shows the trader's mood. Three looks (orb, fox, robot),
 * breathing, blinking, a talking mouth while speaking, and a hop when the mood changes. Tap to switch look.
 * Animation stops when the system animation scale is off.
 */
class CompanionView(context: Context) : View(context) {
    companion object { val CHARACTERS = listOf("Orb", "Fox", "Robot") }

    var character = 0
        set(v) { field = ((v % 3) + 3) % 3; invalidate() }
    var onCharacterChanged: ((Int) -> Unit)? = null
    var speaking = false
        set(v) { field = v; invalidate() }

    private var mood = CompanionMood.Mood.CALM
    private var phase = 0f
    private var hop = 0f
    private var animator: ValueAnimator? = null
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()

    init {
        isClickable = true
        contentDescription = "Desk companion"
        setOnClickListener { character = character + 1; onCharacterChanged?.invoke(character); hopOnce() }
    }

    fun setMood(m: CompanionMood.Mood) {
        if (m == mood) return
        mood = m
        hopOnce()
        invalidate()
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
            duration = 3200L; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
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
        val s = (72 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(resolveSize(s, w), resolveSize(s, h))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val breathe = 1f + 0.04f * sin(phase * 2f * PI.toFloat())
        val lift = -hop * h * 0.12f
        val cx = w / 2f; val cy = h / 2f + lift
        val r = minOf(w, h) * 0.36f * breathe
        val col = colour()
        // glow
        fill.style = Paint.Style.FILL; fill.color = Color.argb(46, Color.red(col), Color.green(col), Color.blue(col))
        canvas.drawCircle(cx, cy, r * 1.25f, fill)
        fill.color = col
        when (character) {
            1 -> { // fox ears
                val ear = android.graphics.Path()
                ear.moveTo(cx - r * 0.95f, cy - r * 0.2f); ear.lineTo(cx - r * 0.75f, cy - r * 1.35f); ear.lineTo(cx - r * 0.1f, cy - r * 0.85f); ear.close()
                ear.moveTo(cx + r * 0.95f, cy - r * 0.2f); ear.lineTo(cx + r * 0.75f, cy - r * 1.35f); ear.lineTo(cx + r * 0.1f, cy - r * 0.85f); ear.close()
                canvas.drawPath(ear, fill)
                canvas.drawCircle(cx, cy, r, fill)
            }
            2 -> { // robot
                rect.set(cx - r, cy - r * 0.9f, cx + r, cy + r * 0.9f)
                canvas.drawRoundRect(rect, r * 0.3f, r * 0.3f, fill)
                line.color = col; line.strokeWidth = r * 0.08f
                canvas.drawLine(cx, cy - r * 0.9f, cx, cy - r * 1.25f, line)
                canvas.drawCircle(cx, cy - r * 1.3f, r * 0.1f, fill)
            }
            else -> canvas.drawCircle(cx, cy, r, fill)
        }
        // face
        val blink = (phase % 0.5f) in 0.48f..0.5f
        val ink = Color.rgb(16, 20, 28)
        fill.color = ink
        val ex = r * 0.38f; val ey = cy - r * 0.15f; val er = r * 0.12f
        val worried = mood == CompanionMood.Mood.WORRIED || mood == CompanionMood.Mood.ALERT
        if (blink) {
            line.color = ink; line.strokeWidth = r * 0.07f
            canvas.drawLine(cx - ex - er, ey, cx - ex + er, ey, line); canvas.drawLine(cx + ex - er, ey, cx + ex + er, ey, line)
        } else {
            val eh = if (mood == CompanionMood.Mood.ALERT) er * 1.5f else er
            canvas.drawCircle(cx - ex, ey, eh, fill); canvas.drawCircle(cx + ex, ey, eh, fill)
        }
        line.color = ink; line.strokeWidth = r * 0.07f
        val my = cy + r * 0.42f
        if (speaking) {
            val open = r * (0.1f + 0.14f * (0.5f + 0.5f * sin(phase * 40f * PI.toFloat())))
            rect.set(cx - r * 0.2f, my - open, cx + r * 0.2f, my + open)
            canvas.drawOval(rect, fill)
        } else {
            rect.set(cx - r * 0.3f, my - r * 0.2f, cx + r * 0.3f, my + r * 0.2f)
            when {
                worried -> canvas.drawArc(rect, 200f, 140f, false, line)
                mood == CompanionMood.Mood.CALM -> canvas.drawLine(cx - r * 0.2f, my, cx + r * 0.2f, my, line)
                else -> canvas.drawArc(rect, 20f, 140f, false, line)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean = super.onTouchEvent(e)
}
