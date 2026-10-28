package com.phoneai.app

/**
 * Routes explicit tool requests from alternate UI surfaces into Agent, the single guarded
 * executor. This is intent routing only: Agent still enforces tool permissions, capability packs,
 * Android permissions, per-action approvals, and safety policy at dispatch.
 */
internal object SharedToolSurfacePolicy {
    private val wakePrefix = Regex("""(?is)^\s*(?:hey\s+)?orion\s*[, :]\s*(.+?)\s*$""")

    /** Return the command body only when the user explicitly addresses Orion. */
    fun commandAfterWake(text: String): String? =
        wakePrefix.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Specialist agents have a separate answer-only model path. Delegate clear execution/data
     * requests rather than letting a specialist claim it can perform actions without dispatching.
     * Ordinary discussion (e.g. "tell me about opening apps") is deliberately not matched.
     */
    fun shouldDelegate(text: String): Boolean {
        if (commandAfterWake(text) != null) return true
        val t = text.trim()
        if (t.isEmpty()) return false
        val patterns = listOf(
            Regex("""(?i)^(please\s+)?(can you\s+|could you\s+|please\s+)?(open|launch|close|switch to|tap|click|press|scroll|swipe|type into|navigate to|read my screen|take a screenshot|record my screen)\b"""),
            Regex("""(?i)^(please\s+)?(search|look up|browse|visit|research|fetch)\b.*\b(web|online|website|latest|current|news|price|weather|release|version|model)\b"""),
            Regex("""(?i)^(what('?s| is)\s+(the\s+)?(current|latest|today'?s|live)\s+)?(weather|news|price|score|forecast|exchange rate|time in)\b"""),
            Regex("""(?i)^(what is|what's|check|find|show me|compare|look up)\b.*\b(current price|price of|price|weather|news|exchange rate|market price|latest update|live score|forecast|model|release|version)\b"""),
            Regex("""(?i)^(set (an? )?(alarm|timer)|turn (on|off) (the )?(flashlight|torch)|send (a )?(message|sms|telegram)|draft (a )?(text|sms|message)|call [a-z0-9+]|check my battery|read my screen|run (this|the) (shell|linux|python|terminal)\b)"""),
            Regex("""(?i)^(remember (this|that)|save (this|to memory)|recall my|show my memories|update my memory|forget this|list my tasks|create a task|add a task|list my skills|run skill)\b"""),
            Regex("""(?i)^(check|show|open) (my )?(connected services|integrations|browser|task list|skills|memory manager|saved tasks)\b""")
        )
        return patterns.any { it.containsMatchIn(t) }
    }
}
