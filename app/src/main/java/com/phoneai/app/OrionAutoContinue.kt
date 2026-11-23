package com.phoneai.app

/**
 * He should not stop halfway and wait for "Continue". When a turn ends on a promise ("Next I'll run stage 5:")
 * after real work, nudge him to do the step instead of describing it. Bounded, so it can never loop forever.
 */
internal object OrionAutoContinue {
    const val MAX_NUDGES = 3
    const val PROMPT = "Continue. Do the next step now with a tool call; do not describe it. If everything is truly finished, give the result in one short message."

    private val promise = Regex("""(?i)(i'?ll (now |next |go ahead and )?|i will (now |next )?|let me (now |next )?|next[,:]? (i|we|up)|now (i'?ll|i will|let'?s)|going to |about to |moving on to|continuing with|proceeding to|on to stage|stage \d+[^.]*:?\s*$)""")
    private val finished = Regex("""(?i)(\bdone\b|\bcomplete[d]?\b|\bfinished\b|all set|here'?s (the|your) |result:|summary:|nothing (left|more)|cannot|can'?t|need you|which one|do you want|would you like|\?\s*$)""")

    fun shouldContinue(finalText: String, toolRounds: Int, nudges: Int): Boolean {
        if (toolRounds == 0 || nudges >= MAX_NUDGES) return false
        val t = finalText.trim()
        if (t.isEmpty() || t.length > 700) return false
        val tail = t.takeLast(400)
        if (finished.containsMatchIn(tail)) return false
        return promise.containsMatchIn(tail) || t.endsWith(":") || t.endsWith("…") || t.endsWith("...")
    }
}
