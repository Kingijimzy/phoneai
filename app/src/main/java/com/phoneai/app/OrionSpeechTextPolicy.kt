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

    private val hash = Regex("(?i)\\b(?:sha-?\\d*[: ]*)?[0-9a-f]{12,}(?:\u2026|\\.\\.\\.)?")
    private val path = Regex("(?<![\\w])/?(?:[\\w.-]+/){2,}[\\w.-]*")
    private val clock = Regex("\\b\\d{1,2}:\\d{2}:\\d{2}\\b")
    private val numberRun = Regex("(?:[-+]?\\d[\\d,]*\\.?\\d*%?[ ,;]+){4,}")

    /** What is worth saying aloud: tables, hashes, paths, timestamps and number runs stay on screen; the voice keeps the point. */
    fun spoken(input: String, maxSentences: Int = 5, maxChars: Int = 700): String {
        val withoutTables = input.lineSequence().filterNot { it.trim().startsWith("|") }.joinToString("\n")
        var t = prepare(withoutTables)
        t = hash.replace(t, " "); t = path.replace(t, "the file"); t = clock.replace(t, " "); t = numberRun.replace(t, " ")
        t = t.replace(Regex("\\s+"), " ").replace(Regex("\\s+([,.;:!?])"), "$1").trim()
        val sentences = Regex("(?<=[.!?])\\s+").split(t).filter { it.isNotBlank() }
        var out = sentences.take(maxSentences).joinToString(" ")
        var cut = sentences.size > maxSentences
        if (out.length > maxChars) { out = out.take(maxChars).substringBeforeLast(' ').trimEnd(',', ';', ':'); cut = true }
        return if (cut) "$out The rest is on screen." else out
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
