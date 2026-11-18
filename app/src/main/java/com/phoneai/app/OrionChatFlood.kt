package com.phoneai.app

/** Keeps the chat readable: older step cards fold away, internal receipts and raw HTML error pages never show. */
internal object OrionChatFlood {
    const val KEEP_PER_RUN = 2

    /** In any run of consecutive note cards only the newest few stay on screen; the transcript still holds them all. */
    fun fold(lines: List<OrionChatLine>): List<OrionChatLine> {
        val out = ArrayList<OrionChatLine>(lines.size)
        var i = 0
        while (i < lines.size) {
            if (lines[i].kind != "note") { out += lines[i]; i++; continue }
            var j = i
            while (j < lines.size && lines[j].kind == "note") j++
            out += lines.subList(i, j).takeLast(KEEP_PER_RUN)
            i = j
        }
        return out
    }

    private val receipts = listOf("LOCAL VERIFY", "OBJECTIVE EVIDENCE RECEIPT", "EVIDENCE RECEIPT", "VERIFY PASS", "VERIFY FAIL")

    /** Drops verification receipts and ids that only the agent needs. */
    fun cleanResult(text: String): String =
        text.lineSequence().filterNot { l -> receipts.any { l.trim().startsWith(it, ignoreCase = true) } }
            .joinToString("\n").trim()

    /** A provider's HTML error page becomes one short sentence; long bodies are trimmed. */
    fun cleanError(message: String?): String {
        val m = message.orEmpty()
        val html = Regex("<\\s*(!doctype|html|head|body)", RegexOption.IGNORE_CASE).find(m)
        if (html != null) {
            val code = Regex("\\b([45]\\d\\d)\\b").find(m.substring(0, html.range.first))?.value
            val who = m.substringBefore(":").takeIf { it.length in 1..40 && !it.contains('<') }
            return (who?.let { "$it: " } ?: "") + "the provider returned an error page" + (code?.let { " (HTTP $it)" } ?: "") + ". It is probably down or unreachable; Orion will try another route."
        }
        return m.replace(Regex("\\s+"), " ").trim().take(400).ifEmpty { "Please try again." }
    }
}
