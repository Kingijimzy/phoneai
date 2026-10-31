package com.phoneai.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.DecelerateInterpolator

/** Cumulative result curve in R. Draws itself in left to right (unless the user turned animations off). */
class EquityCurveView(context: Context) : View(context) {
    private var points: List<Double> = emptyList()
    private var progress = 1f
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * resources.displayMetrics.density; color = Theme.CYAN; strokeJoin = Paint.Join.ROUND }
    private val zero = Paint().apply { color = Theme.LINE; strokeWidth = resources.displayMetrics.density }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.MUTED; textSize = 11f * resources.displayMetrics.scaledDensity }

    fun show(curve: List<Double>) {
        points = curve
        visibility = if (curve.size < 2) GONE else VISIBLE
        if (curve.size < 2) return
        if (!Motion.enabled(context)) { progress = 1f; invalidate(); return }
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700L; interpolator = DecelerateInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
        }.start()
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (120 * resources.displayMetrics.density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (points.size < 2) return
        val pad = 8f * resources.displayMetrics.density
        val lo = minOf(0.0, points.min()); val hi = maxOf(0.0, points.max())
        val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
        fun x(i: Int) = pad + (width - 2 * pad) * i / (points.size - 1)
        fun y(v: Double) = (height - pad - (height - 2 * pad) * ((v - lo) / span)).toFloat()
        canvas.drawLine(pad, y(0.0), width - pad, y(0.0), zero)
        val path = Path()
        val upTo = ((points.size - 1) * progress)
        path.moveTo(x(0), y(points[0]))
        for (i in 1..points.size - 1) {
            if (i <= upTo) path.lineTo(x(i), y(points[i]))
            else { val f = (upTo - (i - 1)).coerceIn(0f, 1f); path.lineTo(x(i - 1) + (x(i) - x(i - 1)) * f, y(points[i - 1] + (points[i] - points[i - 1]) * f)); break }
        }
        canvas.drawPath(path, line)
        canvas.drawText("%+.1fR".format(points.last()), width - pad - label.measureText("%+.1fR".format(points.last())), label.textSize + 2f, label)
    }
}
