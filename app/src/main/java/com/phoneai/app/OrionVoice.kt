package com.phoneai.app

import org.json.JSONObject

/**
 * What Orion shows while he works is what he is actually doing, in his own words or in plain fact.
 * No stock phrases: either he said something real, or we show the real action.
 */
internal object OrionVoice {
    private val stock = Regex("""(?i)^(on it|working on it|let me think|thinking|sure|okay|ok|got it|one moment|just a moment|i'?m working through that now)[.!…]*$""")

    /** First sentence of what he wrote before calling tools, if it says something. */
    fun ownWords(turnText: String): String? {
        val first = turnText.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val sentence = first.split(Regex("(?<=[.!?])\\s+")).first().trim()
        if (sentence.length < 8 || stock.matches(sentence)) return null
        return if (sentence.length > 140) sentence.take(139).trimEnd() + "…" else sentence
    }

    /** The real action in plain fact: tool and its main argument, nothing dressed up. */
    fun action(tool: String, args: JSONObject?): String {
        val subject = listOf("command", "code", "query", "q", "url", "path", "file", "text", "name", "id")
            .firstNotNullOfOrNull { k -> args?.optString(k, "")?.trim()?.takeIf { it.isNotBlank() } }
            ?.lineSequence()?.first()?.replace(Regex("\\s+"), " ").orEmpty()
        val clipped = if (subject.length > 70) subject.take(69).trimEnd() + "…" else subject
        return if (clipped.isBlank()) tool else "$tool: $clipped"
    }

    fun line(turnText: String, tool: String, args: JSONObject?): String = ownWords(turnText) ?: action(tool, args)

    /** A status coming from the agent is already specific; show it as is. */
    fun status(raw: String): String = raw.trim().ifBlank { "…" }.let { it.replaceFirstChar { c -> c.uppercase() } }
}
