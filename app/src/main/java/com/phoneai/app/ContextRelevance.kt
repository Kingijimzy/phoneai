package com.phoneai.app

/**
 * Relevance, not prescription: which context sections ride along with a request.
 *
 * Core sections (identity, objective, memory, capability awareness) always stay. Small sections stay because they are
 * cheap. A large optional section (sensors, routines, trading desk, app usage…) stays only when the request actually
 * shares meaning with it, or when the request is a short reference to earlier work ("continue", "do it"), where nothing
 * can be ruled out. Everything deferred is listed by name and can be pulled on demand with `brain {action:"context"}`,
 * so nothing is ever hidden from Orion, only not pushed at it.
 */
internal object ContextRelevance {
    data class Split(val keep: List<String>, val deferred: List<String>)

    const val CORE_PRIORITY = 90
    const val SMALL_SECTION_CHARS = 700
    // Token count alone is not evidence of a follow-up. Greetings and casual chat are often
    // one or two words; treating every short message as "continue" pushed stale mission,
    // trading, sensor and task context into ordinary conversation.
    private val shortReference = Regex(
        "(?i)^\\s*(?:continue(?:\\s+(?:it|that|the task|where we left off))?|go ahead|proceed|do it|do that|fix it|fix that|test it|test that|finish(?: it| that| the rest)?|complete it|resume|pick up where we left off|where were we|what about it|same|again|yes|yeah|yep|no|nope|it|that|this)\\s*[.!?]*$"
    )

    fun label(section: String): String = section.lineSequence().firstOrNull().orEmpty().replace(Regex("\\s+"), " ").trim().take(72).ifBlank { "unnamed section" }

    fun relevant(section: String, requestTokens: Set<String>): Boolean {
        if (requestTokens.isEmpty()) return false
        val sectionTokens = MemoryRanking.tokens(section.take(6_000))
        return requestTokens.any { it.length >= 4 && it in sectionTokens }
    }

    fun split(sections: List<String>, request: String): Split {
        val tokens = MemoryRanking.tokens(request)
        val referenceOnly = shortReference.matches(request.trim())
        val keep = ArrayList<String>(); val deferred = ArrayList<String>()
        sections.forEach { s ->
            val core = ContextWindowBudget.priority(s) >= CORE_PRIORITY
            // Size is a packing concern, not a relevance signal. Small stale notes were
            // previously injected into greetings simply because they were under 700 chars.
            if (s.isBlank() || core || referenceOnly || relevant(s, tokens)) keep += s else deferred += s
        }
        return Split(keep, deferred)
    }
}
