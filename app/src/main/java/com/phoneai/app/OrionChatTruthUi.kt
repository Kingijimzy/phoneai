package com.phoneai.app

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Chat is the only truth UI (reset Rule 8).
 *
 * Maps real Agent note streams into structured cards. Never invents progress —
 * only renders what the backend already emitted ([CONTEXT], [BRAIN], [TASK:*],
 * [VERIFY:*], [SHELL:*], [BROWSER:*]).
 */
internal object OrionChatTruthUi {

    enum class EventKind(val label: String, val color: Int) {
        CONTEXT("CONTEXT", 0xFF5AA9FF.toInt()),
        BRAIN("BRAIN", 0xFF9AA7FF.toInt()),
        PLAN("PLAN", 0xFF7C9CFF.toInt()),
        TOOL_RUN("RUNNING", 0xFF3FD0C9.toInt()),
        TOOL_OK("RESULT", 0xFF4CD08A.toInt()),
        TOOL_FAIL("FAILED", 0xFFFF6B6B.toInt()),
        VERIFY_REJECT("VERIFY", 0xFFFFA45C.toInt()),
        APPROVAL("APPROVAL", 0xFFFFC857.toInt()),
        CLARIFY("CLARIFY", 0xFFB08CFF.toInt()),
        STATUS("STATUS", 0xFFBFC5CC.toInt()),
        ASSISTANT("ORION", 0xFFE8EEF5.toInt()),
        USER("YOU", 0xFF5AA9FF.toInt())
    }

    data class ParsedEvent(
        val kind: EventKind,
        val title: String,
        val body: String,
        val isFailure: Boolean
    )

    fun parseNote(raw: String): ParsedEvent? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val header = text.lineSequence().firstOrNull().orEmpty()
        val rest = text.lineSequence().drop(1).joinToString("\n").trim()

        return when {
            header.startsWith("[CONTEXT]") || text.startsWith("[CONTEXT]") ->
                ParsedEvent(EventKind.CONTEXT, "Context", rest.ifBlank { text.removePrefix("[CONTEXT]").trim() }, false)

            header.startsWith("[BRAIN]") || text.startsWith("[BRAIN]") ->
                ParsedEvent(EventKind.BRAIN, "Brain", rest.ifBlank { text.removePrefix("[BRAIN]").trim() }, false)

            header.startsWith("[VERIFY:REJECT]") ->
                ParsedEvent(EventKind.VERIFY_REJECT, "Result rejected", rest.ifBlank { text }, true)

            header.startsWith("[TASK:CLARIFY]") ->
                ParsedEvent(EventKind.CLARIFY, "Need clarification", rest.ifBlank { text }, false)

            header.startsWith("[TASK:PLANNED]") || header.startsWith("[TASK:PLAN]") ->
                ParsedEvent(EventKind.PLAN, "Plan", rest.ifBlank { text }, false)

            header.startsWith("[TASK:RUNNING]") || header.startsWith("[TASK:EXECUTE]") ->
                ParsedEvent(EventKind.TOOL_RUN, "Running", rest.ifBlank { text }, false)

            header.startsWith("[TASK:APPROVAL]") ->
                ParsedEvent(EventKind.APPROVAL, "Approval needed", rest.ifBlank { text }, false)

            header.startsWith("[TASK:FAILED]") || header.endsWith(":FAILED]") ->
                ParsedEvent(EventKind.TOOL_FAIL, "Failed", rest.ifBlank { text }, true)

            header.startsWith("[TASK:RESULT]") || header.endsWith(":RESULT]") ||
                header.startsWith("[SHELL:") || header.startsWith("[BROWSER:") ->
                ParsedEvent(
                    if (header.contains("FAILED")) EventKind.TOOL_FAIL else EventKind.TOOL_OK,
                    if (header.contains("FAILED")) "Failed" else "Result",
                    rest.ifBlank { text },
                    header.contains("FAILED")
                )

            header.startsWith("[TASK:COMPLETE]") || header.startsWith("[TASK:RECORD]") ->
                ParsedEvent(EventKind.TOOL_OK, "Complete", rest.ifBlank { text }, false)

            header.startsWith("[TASK:") ->
                ParsedEvent(EventKind.STATUS, header.removePrefix("[").removeSuffix("]"), rest.ifBlank { text }, false)

            else -> null // plain assistant/user text handled elsewhere
        }
    }

    /** Build a structured card view for a parsed backend event. */
    fun card(context: Context, event: ParsedEvent): LinearLayout {
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(Theme.PANEL)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), if (event.isFailure) 0x44FF6B6B else Theme.LINE)
            }
            clipToOutline = true
        }
        card.addView(
            View(context).apply { setBackgroundColor(event.kind.color) },
            LinearLayout.LayoutParams(dp(4), LinearLayout.LayoutParams.MATCH_PARENT)
        )

        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        head.addView(
            TextView(context).apply {
                text = event.kind.label
                textSize = 10.5f
                setTextColor(event.kind.color)
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.08f
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        head.addView(
            TextView(context).apply {
                text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                textSize = 10f
                setTextColor(Theme.MUTED)
            }
        )
        inner.addView(head)

        if (event.title.isNotBlank() && event.title != event.kind.label) {
            inner.addView(
                TextView(context).apply {
                    text = event.title
                    textSize = 13.5f
                    setTextColor(Theme.TEXT)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, 0, 0, dp(2))
                }
            )
        }
        if (event.body.isNotBlank()) {
            inner.addView(
                TextView(context).apply {
                    text = event.body.take(1200)
                    textSize = 13f
                    setTextColor(Theme.TEXT)
                    setTextIsSelectable(true)
                    setLineSpacing(0f, 1.08f)
                }
            )
        }
        card.addView(inner, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        if (Motion.enabled(context)) {
            card.alpha = 0f
            card.translationY = dp(6).toFloat()
            card.animate().alpha(1f).translationY(0f).setDuration(Theme.MOTION_FAST_MS.toLong()).start()
        }
        return card
    }

    /**
     * If [text] is a structured note, return a truth card; otherwise null so the host
     * can fall back to a normal bubble.
     */
    fun tryBuildCard(context: Context, kind: String, text: String): View? {
        val k = kind.lowercase()
        if (k != "note" && k != "status" && k != "ai" && k != "assistant") {
            // still try parse for note-shaped assistant text
        }
        val parsed = parseNote(text) ?: return null
        return card(context, parsed)
    }
}
