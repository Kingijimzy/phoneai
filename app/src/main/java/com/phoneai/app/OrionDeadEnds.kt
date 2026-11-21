package com.phoneai.app

/**
 * Stops "rotating". When different tool calls keep failing the same way (a missing credential, a private repo,
 * an invalid path), the failure is a dead end, not a reason to try yet another tool. The second hit adds a directive
 * to change approach; the third tells Orion to stop and report exactly what it needs.
 */
internal object OrionDeadEnds {
    private data class Hit(var count: Int, var last: Long)
    private val hits = java.util.concurrent.ConcurrentHashMap<String, Hit>()
    private const val WINDOW_MS = 15 * 60_000L

    internal fun classOf(error: String): String =
        error.lowercase().replace(Regex("https?://\\S+|/[\\w./-]+|\"[^\"]*\"|'[^']*'|\\d+"), "#").replace(Regex("\\s+"), " ").trim().removePrefix("error:").trim().take(70)

    fun note(task: String, tool: String, error: String, now: Long = System.currentTimeMillis()): String {
        hits.entries.removeIf { now - it.value.last > WINDOW_MS }
        val cls = classOf(error)
        if (cls.length < 8) return ""
        val h = hits.getOrPut(task.take(80) + "|" + cls) { Hit(0, now) }
        h.count++; h.last = now
        return when {
            h.count >= 3 -> "\nDEAD END (${h.count} times, last tool $tool): \"$cls\". Stop trying routes for this. Tell the owner in one or two sentences exactly what is blocking you and what you need (a credential, access, a file), then continue with whatever part of the job does not depend on it."
            h.count == 2 -> "\nDEAD END WARNING: the same failure again (\"$cls\"). Another tool will hit the same wall. Change the approach completely or work around the blocker; do not retry this route."
            else -> ""
        }
    }
}
