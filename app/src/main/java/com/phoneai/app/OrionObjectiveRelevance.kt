package com.phoneai.app

/**
 * Truth maintenance for the standing objective. A stored objective is background knowledge until the owner's
 * current message actually concerns it; it never turns a greeting or an unrelated question into "resume the old task".
 */
internal object OrionObjectiveRelevance {
    const val STALE_MS = 6L * 60 * 60 * 1000

    /** True when [request] concerns [goal]: a follow-up form ("continue", "fix that", "status") or shared subject words. */
    fun related(goal: String, request: String, ageMs: Long): Boolean {
        if (goal.isBlank() || request.isBlank()) return false
        if (ageMs > STALE_MS) return false
        val r = OrionContextualIntentInterpreter.resolve(request, emptyList(), goal)
        // Shared vocabulary is not proof of continuation: a new question can concern the
        // same broad subject without resuming the old task. Only an explicitly contextual
        // interpretation may attach a standing objective to the current turn.
        val explicitlyResolved = !r.needsClarification &&
            r.kind != OrionContextualIntentInterpreter.Kind.NEW_OBJECTIVE &&
            r.kind != OrionContextualIntentInterpreter.Kind.HISTORICAL_LOOKUP
        if (explicitlyResolved) return true

        // A concrete imperative that modifies the same named subject is a genuine follow-up
        // ("make the cat look more serious"), unlike a question that merely mentions the same
        // broad topic ("how does Orion chat presentation work?").
        val action = Regex("""(?i)^\s*(?:please\s+)?(?:make|change|add|remove|turn|keep|use|replace|adjust|modify|redesign|rewrite|regenerate|edit|update)\b""")
        if (!action.containsMatchIn(request)) return false
        val generic = setOf(
            "please", "make", "change", "add", "remove", "turn", "keep", "use", "replace",
            "adjust", "modify", "redesign", "rewrite", "regenerate", "edit", "update",
            "look", "more", "less", "very", "really", "serious", "better", "different",
            "that", "this", "it", "the", "and", "for", "with"
        )
        val goalSubjects = MemoryRanking.tokens(goal) - generic
        val requestSubjects = MemoryRanking.tokens(request) - generic
        return goalSubjects.intersect(requestSubjects).isNotEmpty()
    }

    /** A message becomes the standing mission only when it asks for work, not when it chats or asks how Orion is. */
    fun isMissionWorthy(request: String): Boolean {
        val t = request.trim()
        if (t.length < 12) return false
        return ConversationAutoRoutePolicy.requiresToolAgent(t) || OrionCommandIntentPolicy.requiresDirectActionRouting(t)
    }
}
