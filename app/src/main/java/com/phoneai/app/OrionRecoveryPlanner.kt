package com.phoneai.app

/**
 * Deterministic recovery guidance for failed or unverified tool outcomes.
 *
 * This never dispatches tools, retries actions, grants permissions, or overrides approval.
 * It gives the next planning turn a bounded strategy based on the observed failure.
 */
internal object OrionRecoveryPlanner {
    enum class Strategy {
        INSPECT_STATE,
        REQUEST_PERMISSION_OR_SETUP,
        CHOOSE_DIFFERENT_METHOD,
        ASK_USER_TO_RESOLVE,
        STOP_AND_REPORT
    }

    data class Guidance(
        val strategy: Strategy,
        val reason: String,
        val mustNotRepeatSameCall: Boolean = true
    ) {
        fun promptLine(): String =
            "RECOVERY GUIDANCE: strategy=${strategy.name.lowercase().replace('_', '-')}; " +
                "reason=$reason; do_not_repeat_identical_call=$mustNotRepeatSameCall. " +
                "This is guidance only: re-check live capabilities and all safety/approval policies before any next action."
    }

    /**
     * Picks only from the caller's freshly checked, registered-and-permitted tool set.
     * This is a shortlist for the next planner turn, not dispatch authorization.
     * Safety/approval denials produce no alternative; uncertain outcomes prefer read-only inspection.
     */
    fun selectAlternatives(
        failedTool: String,
        result: String,
        registeredAndPermitted: Set<String>,
        alreadyAttempted: Set<String> = emptySet(),
        limit: Int = 3
    ): List<String> {
        if (limit <= 0) return emptyList()
        val text = result.lowercase()
        if (listOf("did not approve", "user declined", "not approved", "blocked by safety",
                "safety policy", "user denied", "cancelled", "canceled", "task was stopped")
                .any(text::contains)) return emptyList()

        val preferred = when {
            listOf("target not found", "ambiguous", "stale", "invalid target").any(text::contains) ->
                listOf("read_screen", "wait_for", "click_text", "validate_action")
            listOf("verification", "unverified", "no visible screen change", "timeout", "timed out").any(text::contains) ->
                listOf("read_screen", "verify_action", "wait_for")
            listOf("permission", "accessibility", "not enabled", "setup").any(text::contains) ->
                listOf("read_screen", "list_apps")
            listOf("provider", "network", "http 5", "503", "429", "rate limit").any(text::contains) ->
                listOf("read_screen", "find_files", "list_apps")
            else -> listOf("read_screen", "verify_action", "wait_for", "lookup_app", "list_apps")
        }
        return preferred.asSequence()
            .filter { it != failedTool }
            .filter { it !in alreadyAttempted }
            .filter { it in registeredAndPermitted }
            .distinct()
            .take(limit)
            .toList()
    }

    fun forFailure(tool: String, result: String): Guidance {
        val text = result.lowercase()
        return when {
            text.contains("stopped") || text.contains("cancelled") || text.contains("canceled") ->
                Guidance(Strategy.STOP_AND_REPORT, "The task was stopped or cancelled; do not continue acting.")
            text.contains("did not approve") || text.contains("user declined") || text.contains("not approved") ->
                Guidance(Strategy.STOP_AND_REPORT, "The user declined authorization; do not ask again for the same action in this task.")
            text.contains("permission") || text.contains("accessibility") ||
                text.contains("not connected") || text.contains("not enabled") ||
                text.contains("not ready") || text.contains("setup") ->
                Guidance(Strategy.REQUEST_PERMISSION_OR_SETUP, "A prerequisite is missing; explain the exact setting or setup needed, then rediscover capability state.")
            text.contains("verification failed") || text.contains("verification unavailable") ||
                text.contains("unverified") || text.contains("no visible screen change") ->
                Guidance(Strategy.INSPECT_STATE, "The action outcome is not proven; inspect current state before deciding whether the goal is already satisfied.")
            text.contains("ambiguous") || text.contains("target") && text.contains("not found") ||
                text.contains("invalid") && (tool.contains("click") || tool == "type") ->
                Guidance(Strategy.CHOOSE_DIFFERENT_METHOD, "The target or arguments did not resolve; refresh screen evidence and select a grounded target instead of reusing stale arguments.")
            text.contains("timeout") || text.contains("temporarily unavailable") ||
                text.contains("rate limit") || text.contains("429") ->
                Guidance(Strategy.ASK_USER_TO_RESOLVE, "A transient external failure occurred; avoid rapid retries and report the blocker or wait for an explicit continuation.")
            text.contains("sensitive") || text.contains("safety") || text.contains("refuses") ||
                text.contains("blocked by safety") || text.contains("consequential") ->
                Guidance(Strategy.STOP_AND_REPORT, "A safety boundary blocked the action; do not search for a bypass.")
            else ->
                Guidance(Strategy.CHOOSE_DIFFERENT_METHOD, "The tool failed; inspect the observed state and choose a meaningfully different safe method.")
        }
    }
}
