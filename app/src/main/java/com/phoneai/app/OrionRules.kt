package com.phoneai.app

import android.content.Context

/**
 * Standing rules Orion learns from how Kingi talks ("from now on…", "never…", "always…", "I prefer X over Y").
 * Detection is pure; storage is a small capped list injected into every chat prompt.
 */
internal object OrionRules {
    private val patterns = listOf(
        Regex("""(?i)^(?:ok(?:ay)?[,. ]+|and\s+)?(?:from now on|going forward|from here on)[,: ]+(.{6,200})"""),
        Regex("""(?i)\b((?:always|never)\s+.{4,160})"""),
        Regex("""(?i)\b(i\s+prefer\s+.{3,80}?\s+(?:over|to|instead of)\s+.{3,80})"""),
        Regex("""(?i)^(?:no[,. ]+)?(?:don'?t|do not|stop)\s+(.{4,160}?)(?:[,;]\s*((?:use|do|say)\s+.{3,120}))?$"""),
        Regex("""(?i)\b(?:remember (?:to|that)|keep in mind (?:that)?)\s+(.{6,200})""")
    )
    private val QUESTION = Regex("""(?i)^\s*(?:why|how|what|when|where|who|can|could|should|is|are|do|does|did|will|would)\b""")

    /** Returns the rule text, or null when the message is not a standing instruction. Pure. */
    fun detect(text: String): String? {
        val t = text.trim().replace(Regex("\\s+"), " ")
        if (t.length < 10 || t.length > 320 || t.endsWith("?") || QUESTION.containsMatchIn(t)) return null
        for ((i, p) in patterns.withIndex()) {
            val m = p.find(t) ?: continue
            val body = m.groupValues.drop(1).filter { it.isNotBlank() }.joinToString(" → ").trim().trimEnd('.', '!', ',')
            if (body.length < 6) continue
            return when (i) {
                0, 4 -> body
                3 -> "Do not " + body
                else -> body
            }.replaceFirstChar { it.uppercase() }
        }
        return null
    }

    private const val PREFS = "orion_rules_v1"
    private const val KEY = "rules"
    private const val MAX = 25

    fun all(c: Context): List<String> =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
            .split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    /** Pure merge: newest last, case-insensitive duplicates dropped, capped. */
    fun merge(existing: List<String>, rule: String): List<String> =
        (existing.filterNot { it.equals(rule, true) } + rule).takeLast(MAX)

    fun learn(c: Context, userText: String): String? {
        val rule = detect(userText) ?: return null
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, merge(all(c), rule).joinToString("\n")).apply()
        return rule
    }

    fun forget(c: Context, needle: String): Int {
        val keep = all(c).filterNot { it.contains(needle, true) }
        val removed = all(c).size - keep.size
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, keep.joinToString("\n")).apply()
        return removed
    }

    fun promptBlock(c: Context): String {
        val rules = all(c)
        return if (rules.isEmpty()) "" else "STANDING RULES (Kingi told you these; they outrank your defaults):\n" + rules.joinToString("\n") { "- $it" }
    }
}

/** Retry decisions and Retry-After parsing for model and web calls. Pure. */
internal object OrionRetryPolicy {
    fun isRetryable(status: Int?): Boolean = status == null || status == 408 || status == 429 || status in 500..599

    /** Seconds or an HTTP-date; returns whole seconds, 0..300, or -1 when absent/unparseable. */
    fun parseRetryAfter(header: String?, nowMs: Long = System.currentTimeMillis()): Int {
        val h = header?.trim().orEmpty()
        if (h.isEmpty()) return -1
        h.toDoubleOrNull()?.let { return it.toInt().coerceIn(0, 300) }
        val date = runCatching {
            java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US).parse(h)?.time
        }.getOrNull() ?: return -1
        return ((date - nowMs) / 1000).toInt().coerceIn(0, 300)
    }

    /** Exponential backoff with a floor of the server's own Retry-After. */
    fun delayMs(attempt: Int, retryAfterSec: Int): Long =
        maxOf((retryAfterSec.coerceAtLeast(0)) * 1000L, (500L shl attempt.coerceIn(0, 6)).coerceAtMost(30_000L))
}
