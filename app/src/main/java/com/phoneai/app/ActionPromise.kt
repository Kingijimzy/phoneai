package com.phoneai.app

/**
 * Catches the failure where the model says it will do something ("I'll check...", "ACTION") but returns
 * no tool call, so the turn ends with nothing done. Pure logic, unit tested.
 */
internal object ActionPromise {
    private val promise = Regex(
        "(?i)\\b(i'll|i will|let me|let's|i'm going to|i am going to|checking|looking|searching|opening|connecting|running)\\b"
    )
    private val bareAction = Regex("^\\s*(ACTION|WORKING|RUNNING)\\s*$")

    /**
     * True when a reply with no tool call reads as an announced next step rather than a finished answer.
     * A question to the user is not a promise, and neither is a completed answer that merely mentions a verb.
     */
    fun announcesUnfinishedStep(say: String, toolCallsThisTurn: Int): Boolean {
        if (toolCallsThisTurn > 0) return false
        val text = say.trim()
        if (text.isEmpty()) return false
        if (bareAction.matches(text)) return true
        if (text.endsWith("?")) return false
        return promise.containsMatchIn(text)
    }

    /** The one corrective message sent back to the model. */
    const val NUDGE = "You announced a next step but did not call a tool, so nothing was done. Call the appropriate tool now with ONE complete JSON step. If no tool can do it, say plainly what is missing instead of announcing an action."
}
