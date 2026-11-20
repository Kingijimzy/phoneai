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

    /** True for failures a retry or another route will usually cure: timeouts, 408/429/5xx, dropped connections. */
    fun isTransient(message: String?): Boolean {
        val m = message.orEmpty().lowercase()
        if (Regex("\\b(408|425|429|500|502|503|504)\\b").containsMatchIn(m)) return true
        return listOf("timed out", "timeout", "too slowly", "unable to resolve", "connection reset", "unexpected end of stream", "failed to connect", "connection abort", "rate limit", "overloaded").any { it in m }
    }

    /** A provider's HTML error page becomes one short sentence; long bodies are trimmed. */
    fun cleanError(message: String?): String {
        val m = message.orEmpty()
        val html = Regex("<\\s*(!doctype|html|head|body)", RegexOption.IGNORE_CASE).find(m)
        if (html != null) {
            val code = Regex("\\b([45]\\d\\d)\\b").find(m.substring(0, html.range.first))?.value
            val who = m.substringBefore(":").takeIf { it.length in 1..40 && !it.contains('<') }
            return (who?.let { "$it: " } ?: "") + "the provider returned an error page" + (code?.let { " (HTTP $it)" } ?: "") + ". It is probably down or unreachable; Orion will try another route."
        }
        if (isTransient(m) && m.contains('{')) {
            val code = Regex("\\b([45]\\d\\d)\\b").find(m)?.value
            return "the provider was too slow or busy" + (code?.let { " (HTTP $it)" } ?: "") + "."
        }
        return m.replace(Regex("\\s+"), " ").trim().take(400).ifEmpty { "Please try again." }
    }
}
