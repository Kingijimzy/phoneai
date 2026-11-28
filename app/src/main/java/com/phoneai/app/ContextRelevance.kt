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
    private const val SHORT_REFERENCE_TOKENS = 3

    fun label(section: String): String = section.lineSequence().firstOrNull().orEmpty().replace(Regex("\\s+"), " ").trim().take(72).ifBlank { "unnamed section" }

    fun relevant(section: String, requestTokens: Set<String>): Boolean {
        if (requestTokens.isEmpty()) return false
        // Stemmed via OrionRelevance (shared with every other relevance decision), not raw word equality,
        // so "building the feature" still finds a section about "builds" instead of missing it on a technicality.
        val sectionTokens = OrionRelevance.tokens(section.take(6_000))
        return requestTokens.any { it.length >= 4 && it in sectionTokens }
    }

    fun split(sections: List<String>, request: String): Split {
        val tokens = OrionRelevance.tokens(request)
        val referenceOnly = tokens.size <= SHORT_REFERENCE_TOKENS
        val keep = ArrayList<String>(); val deferred = ArrayList<String>()
        sections.forEach { s ->
            val core = ContextWindowBudget.priority(s) >= CORE_PRIORITY
            if (s.isBlank() || core || referenceOnly || s.length <= SMALL_SECTION_CHARS || relevant(s, tokens)) keep += s else deferred += s
        }
        return Split(keep, deferred)
    }
}
