package com.phoneai.app

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Classic graphite activity card. Compact at rest; details expand on demand. */
internal class OrionLiveActivityCard(
    context: Context,
    private val event: OrionActivityEvent,
    private val elapsed: String? = null
) : LinearLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun surface(color: Int, stroke: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(14).toFloat()
        setStroke(dp(1), stroke)
    }

    init {
        orientation = VERTICAL
        setPadding(dp(13), dp(11), dp(13), dp(11))
        background = surface(0xFF181818.toInt(), 0xFF484848.toInt())
        isFocusable = true
        isClickable = true

        val heading = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text = event.title
            textSize = 13f
            setTextColor(0xFFF0F0F0.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2
        }
        heading.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val state = TextView(context).apply {
            text = statusLabel(event.status)
            textSize = 10f
            letterSpacing = .04f
            setTextColor(statusColor(event.status))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = surface(0xFF242424.toInt(), 0xFF555555.toInt())
        }
        heading.addView(state)
        addView(heading)

        val summary = TextView(context).apply {
            text = event.summary.ifBlank { kindLabel(event.kind) }
            textSize = 12f
            setTextColor(0xFFB8B8B8.toInt())
            setPadding(0, dp(5), 0, 0)
            maxLines = 2
        }
        addView(summary)

        val meta = TextView(context).apply {
            text = buildString {
                append(kindLabel(event.kind).uppercase())
                elapsed?.let { append("  ·  ").append(it) }
                event.agentId?.let { append("  ·  ").append(it) }
            }
            textSize = 9f
            letterSpacing = .07f
            setTextColor(0xFF888888.toInt())
            setPadding(0, dp(7), 0, 0)
        }
        addView(meta)

        val details = LinearLayout(context).apply {
            orientation = VERTICAL
            visibility = View.GONE
            setPadding(0, dp(9), 0, 0)
        }
        if (event.evidence.isNotEmpty()) {
            event.evidence.take(8).forEach { item ->
                details.addView(TextView(context).apply {
                    text = "• $item"
                    textSize = 11f
                    setTextColor(0xFFCDCDCD.toInt())
                    setPadding(0, dp(3), 0, dp(3))
                    setTextIsSelectable(true)
                })
            }
        } else {
            details.addView(TextView(context).apply {
                text = "No additional evidence was attached to this event."
                textSize = 11f
                setTextColor(0xFF888888.toInt())
            })
        }
        addView(details)
        setOnClickListener {
            val expanded = details.visibility != View.VISIBLE
            details.visibility = if (expanded) View.VISIBLE else View.GONE
            summary.maxLines = if (expanded) 8 else 2
            contentDescription = if (expanded) "Collapse activity details" else "Expand activity details"
        }
        contentDescription = "Expand activity details for ${event.title}"
    }

    private fun kindLabel(kind: OrionActivityKind) = when (kind) {
        OrionActivityKind.THINKING -> "Thinking"
        OrionActivityKind.RESEARCH -> "Research"
        OrionActivityKind.CODING -> "Coding"
        OrionActivityKind.TOOL -> "Tool execution"
        OrionActivityKind.DELEGATION -> "Agent hand-off"
        OrionActivityKind.APPROVAL -> "Approval"
        OrionActivityKind.ERROR -> "Error"
        OrionActivityKind.COMPLETION -> "Completed"
        OrionActivityKind.INTERRUPTED -> "Interrupted"
        OrionActivityKind.CANCELLED -> "Cancelled"
    }

    private fun statusLabel(status: OrionActivityStatus) = when (status) {
        OrionActivityStatus.ACTIVE -> "ACTIVE"
        OrionActivityStatus.WAITING -> "WAITING"
        OrionActivityStatus.SUCCEEDED -> "DONE"
        OrionActivityStatus.FAILED -> "FAILED"
        OrionActivityStatus.CANCELLED -> "CANCELLED"
        OrionActivityStatus.INTERRUPTED -> "INTERRUPTED"
    }

    private fun statusColor(status: OrionActivityStatus): Int = when (status) {
        OrionActivityStatus.ACTIVE -> 0xFFD0D0D0.toInt()
        OrionActivityStatus.WAITING -> 0xFFE0C58A.toInt()
        OrionActivityStatus.SUCCEEDED -> 0xFFB8CDBD.toInt()
        OrionActivityStatus.FAILED -> 0xFFE09B9B.toInt()
        OrionActivityStatus.CANCELLED, OrionActivityStatus.INTERRUPTED -> 0xFFAAAAAA.toInt()
    }
}
