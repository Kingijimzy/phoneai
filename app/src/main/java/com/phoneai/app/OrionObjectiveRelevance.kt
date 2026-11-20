package com.phoneai.app

/**
 * Truth maintenance for the standing objective. A stored objective is background knowledge until the owner's
 * current message actually concerns it; it never turns a greeting or an unrelated question into "resume the old task".
 */
internal object OrionObjectiveRelevance {
    const val STALE_MS = 6L * 60 * 60 * 1000

    private val stop = setOf("the", "and", "for", "with", "that", "this", "you", "your", "what", "how", "are", "doing", "today", "have", "can", "please", "make", "from", "about", "just", "now", "hey", "bro", "how", "its", "was", "not", "did", "why", "who", "has", "had", "but", "too", "all", "one", "get", "got", "any", "see", "tell", "give", "want", "need", "know", "here", "there", "right", "okay")

    private fun tokens(s: String) = Regex("[\\p{L}\\p{N}]{3,}").findAll(s.lowercase()).map { it.value }.filter { it !in stop }.toSet()

    /** True when [request] concerns [goal]: a follow-up form ("continue", "fix that", "status") or shared subject words. */
    fun related(goal: String, request: String, ageMs: Long): Boolean {
        if (goal.isBlank() || request.isBlank()) return false
        if (ageMs > STALE_MS) return false
        val r = OrionContextualIntentInterpreter.resolve(request, emptyList(), goal)
        val followUp = !r.needsClarification &&
            r.kind != OrionContextualIntentInterpreter.Kind.NEW_OBJECTIVE &&
            r.kind != OrionContextualIntentInterpreter.Kind.HISTORICAL_LOOKUP
        return followUp || tokens(goal).intersect(tokens(request)).isNotEmpty()
    }

    /** A message becomes the standing mission only when it asks for work, not when it chats or asks how Orion is. */
    fun isMissionWorthy(request: String): Boolean {
        val t = request.trim()
        if (t.length < 12) return false
        return ConversationAutoRoutePolicy.requiresToolAgent(t) || OrionCommandIntentPolicy.requiresDirectActionRouting(t)
    }
}
