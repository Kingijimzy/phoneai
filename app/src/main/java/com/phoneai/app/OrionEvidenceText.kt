package com.phoneai.app

/** Pure helpers for the "UNTRUSTED REFERENCE DATA" wrapper that models see around web results. Users must never see it. */
internal object OrionEvidenceText {
    private const val MARKER = "UNTRUSTED REFERENCE DATA"

    /** Index just past the JSON object that starts at [open], or -1. String-aware brace matching. */
    private fun endOfJson(text: String, open: Int): Int {
        var depth = 0; var inString = false; var escaped = false
        var i = open
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i + 1 }
            }
            i++
        }
        return -1
    }

    /** Pulls the plain "content" text out of a wrapped result; returns the input unchanged if it is not wrapped. */
    fun unwrap(raw: String): String {
        if (!raw.startsWith(MARKER)) return raw
        val open = raw.indexOf('{')
        if (open < 0) return raw
        val end = endOfJson(raw, open)
        if (end < 0) return raw
        return try {
            org.json.JSONObject(raw.substring(open, end)).optString("content", "").ifBlank { raw }
        } catch (_: Exception) { raw }
    }

    /** Removes every wrapper block (marker line plus its JSON payload) from text shown to the user. */
    fun strip(text: String): String {
        var out = text
        var guard = 0
        while (guard++ < 5) {
            val at = out.indexOf(MARKER)
            if (at < 0) break
            val open = out.indexOf('{', at)
            val end = if (open < 0) -1 else endOfJson(out, open)
            out = if (end < 0) out.substring(0, at) else out.substring(0, at) + out.substring(end)
        }
        return out.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /** Up to [limit] http(s) links from plain search text, preferring reputable score and news hosts. */
    fun links(text: String, limit: Int = 2): List<String> {
        val all = Regex("https?://[^\\s\"'<>)]+").findAll(text).map { it.value.trimEnd('.', ',', ';') }.distinct().toList()
        val preferred = listOf("espn.com", "bbc.", "livescore.com", "fcbarcelona.com", "laliga.com", "skysports.com", "goal.com", "reuters.com", "apnews.com")
        return (all.filter { u -> preferred.any { u.contains(it) } && !u.contains("/undefined/") } + all)
            .distinct().take(limit)
    }
}
