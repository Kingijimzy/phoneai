package com.phoneai.app

/** Keeps the chat readable: long code and tool dumps stay in Files/Studio, chat shows a short pointer. */
internal object OrionChatText {
    private const val MAX_INLINE_CODE_LINES = 8
    private val fence = Regex("```[^\\n]*\\n([\\s\\S]*?)(```|$)")

    fun tidy(text: String): String {
        if (!text.contains("```")) return text
        return fence.replace(text) { m ->
            val body = m.groupValues[1].trimEnd()
            val lines = if (body.isEmpty()) 0 else body.lines().size
            val closed = m.groupValues[2] == "```"
            when {
                !closed -> "▤ writing code…"
                lines > MAX_INLINE_CODE_LINES -> "▤ code · $lines lines — saved to your workspace, open it from Files or Studio"
                else -> m.value
            }
        }
    }
}
