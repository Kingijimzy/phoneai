package com.phoneai.app

/**
 * Converts Orion's final user-facing answer into speech-safe prose.
 * This is a presentation boundary, not a substitute for upstream response validation.
 * Executable/source blocks and internal execution traces are excluded, not narrated.
 */
internal object OrionSpeechTextPolicy {
    private val fencedCode = Regex("(?s)\\x60\\x60\\x60.*?\\x60\\x60\\x60|~~~.*?~~~")
    private val inlineCode = Regex("\\x60[^\\x60]*\\x60")
    private val url = Regex("(?i)\\b(?:https?://|www\\.)\\S+")
    private val markdownLink = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val htmlTag = Regex("<[^>]{1,300}>")
    private val internalLine = Regex(
        "(?i)^\\s*(?:assistant\\s+to=|tool\\s+(?:call|result)\\s*:|analysis\\s*:|commentary\\s*:|" +
            "debug\\s*:|stack trace\\s*:|exception in thread|at [\\w.$]+\\([^)]*\\)|(?:package|import|fun|val|var|class|object|interface)\\s+|BUILD SUCCESSFUL|>\\s*Task\\s*:)"
    )
    private val structuredLine = Regex("^\\s*(?:\\{.*[}:,]\\s*|\\[.*[\\],]\\s*|<\\?xml|</?[A-Za-z][^>]*>)\\s*$")

    fun prepare(input: String): String {
        var text = input.trim()
        if (text.isEmpty() || isStructuredPayload(text)) return ""

        text = fencedCode.replace(text, " ")
        text = inlineCode.replace(text, " ")
        text = markdownLink.replace(text, "$1")
        text = url.replace(text, "the link")
        text = htmlTag.replace(text, " ")

        val proseLines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { internalLine.containsMatchIn(it.replace(Regex("[*_~]"), "")) }
            .filterNot { structuredLine.matches(it) }
            .map { sourceLine ->
                val line = if (sourceLine.startsWith("|") && sourceLine.count { ch -> ch == '|' } >= 2) {
                    val cells = sourceLine.trim().trim('|').split('|').map { it.trim() }
                    if (cells.isNotEmpty() && cells.all { it.matches(Regex("[-: ]+")) }) "" else cells.joinToString(", ")
                } else sourceLine
                line.replace(Regex("^#{1,6}\\s*"), "")
                    .replace(Regex("^\\s*(?:[-*+]\\s+|\\d+[.)]\\s+)"), "")
                    .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "\$1")
                    .replace(Regex("[*_~]{1,3}"), "")
                    .replace(Regex("\\s+"), " ")
                    .trim()
            }
            .filter { it.isNotEmpty() }
            .toList()

        return proseLines.joinToString(". ")
            .replace(Regex("\\s+([,.;:!?])"), "\$1")
            .replace(Regex("([.!?])(?:\\s*\\1)+"), "\$1")
            .trim()
            .take(12_000)
    }

    private fun isStructuredPayload(text: String): Boolean {
        val value = text.trim()
        if (value.length < 2) return false
        val jsonLike = (value.startsWith("{") && value.endsWith("}")) ||
            (value.startsWith("[") && value.endsWith("]"))
        if (jsonLike && (value.contains("\":") || value.contains("\",") || value.contains("{\""))) {
            return true
        }
        return value.startsWith("<?xml", ignoreCase = true) ||
            (value.startsWith("<") && value.endsWith(">") && value.count { it == '<' } > 1)
    }
}
