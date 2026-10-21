package com.phoneai.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Lightweight, dependency-free line icons for Orion's native UI.
 * Drawn at runtime so icons stay crisp on small screens and don't add image assets.
 */
class OrionIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var icon: String = "spark"
        set(value) { field = value; invalidate() }
    var tint: Int = Theme.CYAN
        set(value) { field = value; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.1f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = min(width, height).toFloat()
        if (side <= 0f) return
        val save = canvas.save()
        val inset = side * 0.17f
        canvas.translate((width - side) / 2f + inset, (height - side) / 2f + inset)
        val unit = (side - inset * 2f) / 24f
        canvas.scale(unit, unit)
        paint.color = tint
        paint.strokeWidth = 1.8f
        paint.style = Paint.Style.STROKE
        path.reset()
        when (icon.lowercase()) {
            "home" -> {
                path.moveTo(2f, 11f); path.lineTo(12f, 3f); path.lineTo(22f, 11f)
                path.moveTo(5f, 10f); path.lineTo(5f, 21f); path.lineTo(10f, 21f)
                path.lineTo(10f, 15f); path.lineTo(14f, 15f); path.lineTo(14f, 21f)
                path.lineTo(19f, 21f); path.lineTo(19f, 10f)
                canvas.drawPath(path, paint)
            }
            "chat", "message" -> {
                canvas.drawRoundRect(2.5f, 3f, 21.5f, 17f, 4f, 4f, paint)
                path.moveTo(7f, 17f); path.lineTo(5f, 21f); path.lineTo(12f, 17f)
                canvas.drawPath(path, paint)
                canvas.drawCircle(8f, 10f, 0.7f, paint); canvas.drawCircle(12f, 10f, 0.7f, paint); canvas.drawCircle(16f, 10f, 0.7f, paint)
            }
            "tools", "spark", "skills" -> {
                path.moveTo(12f, 2f); path.lineTo(14f, 9f); path.lineTo(21f, 12f)
                path.lineTo(14f, 14f); path.lineTo(12f, 22f); path.lineTo(10f, 14f)
                path.lineTo(3f, 12f); path.lineTo(10f, 9f); path.close()
                canvas.drawPath(path, paint)
                canvas.drawCircle(19.5f, 4.5f, 1.4f, paint)
            }
            "settings" -> {
                canvas.drawCircle(12f, 12f, 4f, paint)
                for (i in 0 until 8) {
                    val a = Math.PI * i / 4.0
                    val x1 = 12f + kotlin.math.cos(a).toFloat() * 6f
                    val y1 = 12f + kotlin.math.sin(a).toFloat() * 6f
                    val x2 = 12f + kotlin.math.cos(a).toFloat() * 9f
                    val y2 = 12f + kotlin.math.sin(a).toFloat() * 9f
                    canvas.drawLine(x1, y1, x2, y2, paint)
                }
            }
            "task", "tasks", "checklist" -> {
                for (y in listOf(5f, 12f, 19f)) {
                    canvas.drawRoundRect(2f, y - 2f, 7f, y + 3f, 1f, 1f, paint)
                    canvas.drawLine(10f, y + 0.5f, 22f, y + 0.5f, paint)
                }
            }
            "memory" -> {
                canvas.drawRoundRect(3f, 3f, 21f, 21f, 4f, 4f, paint)
                canvas.drawLine(8f, 8f, 16f, 8f, paint)
                canvas.drawLine(8f, 12f, 17f, 12f, paint)
                canvas.drawLine(8f, 16f, 14f, 16f, paint)
            }
            "browser", "web", "research" -> {
                canvas.drawCircle(12f, 12f, 9f, paint)
                canvas.drawOval(7f, 3f, 17f, 21f, paint)
                canvas.drawLine(3.5f, 9f, 20.5f, 9f, paint)
                canvas.drawLine(3.5f, 15f, 20.5f, 15f, paint)
            }
            "voice", "mic" -> {
                canvas.drawRoundRect(8f, 2f, 16f, 15f, 4f, 4f, paint)
                path.moveTo(4f, 11f); path.lineTo(4f, 13f); path.quadTo(4f, 20f, 12f, 20f)
                path.quadTo(20f, 20f, 20f, 13f); path.lineTo(20f, 11f)
                path.moveTo(12f, 20f); path.lineTo(12f, 23f)
                path.moveTo(8f, 23f); path.lineTo(16f, 23f)
                canvas.drawPath(path, paint)
            }
            "network", "radar" -> {
                canvas.drawCircle(12f, 12f, 2f, paint)
                canvas.drawArc(5f, 5f, 19f, 19f, 210f, 120f, false, paint)
                canvas.drawArc(1f, 1f, 23f, 23f, 220f, 100f, false, paint)
                canvas.drawLine(12f, 12f, 20f, 4f, paint)
            }
            "shield", "privacy" -> {
                path.moveTo(12f, 2f); path.lineTo(21f, 5f); path.lineTo(20f, 13f)
                path.quadTo(18f, 19f, 12f, 22f); path.quadTo(6f, 19f, 4f, 13f)
                path.lineTo(3f, 5f); path.close(); canvas.drawPath(path, paint)
                path.reset(); path.moveTo(8f, 12f); path.lineTo(11f, 15f); path.lineTo(17f, 9f)
                canvas.drawPath(path, paint)
            }
            "stop" -> canvas.drawRoundRect(5f, 5f, 19f, 19f, 2f, 2f, paint)
            "close", "x" -> {
                path.moveTo(5f, 5f); path.lineTo(19f, 19f)
                path.moveTo(19f, 5f); path.lineTo(5f, 19f)
                canvas.drawPath(path, paint)
            }
            "pause" -> {
                canvas.drawRoundRect(6f, 4f, 10f, 20f, 1f, 1f, paint)
                canvas.drawRoundRect(14f, 4f, 18f, 20f, 1f, 1f, paint)
            }
            "plus", "new" -> {
                canvas.drawLine(12f, 4f, 12f, 20f, paint); canvas.drawLine(4f, 12f, 20f, 12f, paint)
            }
            "image", "photo", "attach" -> {
                canvas.drawRoundRect(2.5f, 3.5f, 21.5f, 20.5f, 3f, 3f, paint)
                canvas.drawCircle(8f, 8.5f, 1.7f, paint)
                path.moveTo(4f, 17f); path.lineTo(9f, 12f); path.lineTo(12f, 15f)
                path.lineTo(16f, 10f); path.lineTo(20f, 15f)
                canvas.drawPath(path, paint)
            }
            "send" -> {
                path.moveTo(3f, 11f); path.lineTo(21f, 3f); path.lineTo(15f, 21f)
                path.lineTo(11f, 13f); path.close(); path.moveTo(11f, 13f); path.lineTo(21f, 3f)
                canvas.drawPath(path, paint)
            }
            "clock", "history" -> {
                canvas.drawCircle(12f, 12f, 9f, paint)
                path.moveTo(12f, 6f); path.lineTo(12f, 12f); path.lineTo(16f, 14f)
                canvas.drawPath(path, paint)
            }
            "check" -> {
                path.moveTo(4f, 12f); path.lineTo(9f, 17f); path.lineTo(20f, 6f)
                canvas.drawPath(path, paint)
            }
            "call", "phone" -> {
                path.moveTo(6f, 3f); path.lineTo(9f, 8f); path.lineTo(7f, 10f)
                path.quadTo(10f, 16f, 14f, 17f); path.lineTo(16f, 15f); path.lineTo(21f, 18f)
                path.lineTo(20f, 21f); path.quadTo(19f, 23f, 16f, 22f)
                path.quadTo(6f, 18f, 2f, 8f); path.quadTo(1f, 4f, 4f, 3f); path.close()
                canvas.drawPath(path, paint)
            }
            "record" -> {
                canvas.drawCircle(12f, 12f, 8.5f, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(12f, 12f, 4.5f, paint)
                paint.style = Paint.Style.STROKE
            }
            "arrow" -> {
                path.moveTo(4f, 12f); path.lineTo(20f, 12f)
                path.moveTo(13f, 5f); path.lineTo(20f, 12f); path.lineTo(13f, 19f)
                canvas.drawPath(path, paint)
            }
            else -> {
                canvas.drawCircle(12f, 12f, 8f, paint)
                canvas.drawCircle(12f, 12f, 2f, paint)
                canvas.drawLine(12f, 1f, 12f, 4f, paint)
                canvas.drawLine(12f, 20f, 12f, 23f, paint)
            }
        }
        canvas.restoreToCount(save)
    }
}
