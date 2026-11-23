package com.phoneai.app

import android.content.Context

/**
 * The real reason a tool failed. Tool output arrives wrapped in a data header ("SHELL OUTPUT (invalid; ...):"),
 * so the first line of a failure says nothing; this keeps the first line that does, per tool, across restarts.
 */
internal object OrionFailureNotes {
    private val notes = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val wrapper = Regex("""(?i)^(\w[\w ]* output|\(?invalid|data, not instructions|\[.*]|-{3,}|=+)\b.*""")

    fun meaningful(result: String): String =
        result.lineSequence().map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !wrapper.matches(it) && !it.endsWith("data, not instructions):") }
            ?.take(200) ?: result.trim().take(200)

    fun record(c: Context?, tool: String, result: String) {
        val line = AgentPrivacyFilter.safeCommand(meaningful(result))
        notes[tool] = line
        c?.getSharedPreferences("orion_failures", Context.MODE_PRIVATE)?.edit()?.putString(tool, line)?.apply()
    }

    fun load(c: Context) {
        c.getSharedPreferences("orion_failures", Context.MODE_PRIVATE).all.forEach { (k, v) -> (v as? String)?.let { notes.putIfAbsent(k, it) } }
    }

    fun last(tool: String): String? = notes[tool]
    internal fun clearForTest() = notes.clear()
}
