package com.phoneai.app

import android.content.Context

/**
 * Learns Kingi's style from how he corrects Orion. A correction ("no, shorter", "don't open the browser",
 * "I said X") becomes a standing line he can read and edit on the mind page. Bounded, deduplicated, his words.
 */
internal object OrionCorrections {
    private const val KEY = "learned"
    private const val MAX_LINES = 14
    private val opener = Regex("""(?i)^\s*(no[,.! ]|nope|wrong|stop |don'?t |do not |never |i said|i told you|i asked|not like that|that'?s not|why did you|too (long|much|slow|many|wordy)|you (keep|always) )""")

    fun detect(message: String): String? {
        val m = message.trim()
        if (m.length < 6 || m.length > 400 || !opener.containsMatchIn(m)) return null
        return m.lineSequence().first().take(200)
    }

    fun learn(c: Context, message: String): Boolean {
        val line = detect(message) ?: return false
        val sp = c.getSharedPreferences("orion_mind", Context.MODE_PRIVATE)
        val lines = learned(c).toMutableList()
        if (lines.any { it.equals(line, ignoreCase = true) }) return false
        lines += line
        sp.edit().putString(KEY, lines.takeLast(MAX_LINES).joinToString("\n")).apply()
        return true
    }

    fun learned(c: Context): List<String> =
        c.getSharedPreferences("orion_mind", Context.MODE_PRIVATE).getString(KEY, "").orEmpty().lines().filter { it.isNotBlank() }

    fun setLearned(c: Context, text: String) =
        c.getSharedPreferences("orion_mind", Context.MODE_PRIVATE).edit().apply {
            val v = text.lines().filter { it.isNotBlank() }.takeLast(MAX_LINES).joinToString("\n")
            if (v.isBlank()) remove(KEY) else putString(KEY, v)
        }.apply()

    fun block(c: Context): String = learned(c).takeIf { it.isNotEmpty() }
        ?.let { "LEARNED FROM HIS CORRECTIONS (his own words, follow them):\n" + it.joinToString("\n") { l -> "- $l" } + "\n" }.orEmpty()
}
