package com.phoneai.app

/**
 * Resolves conversational references before the planner sees a request.
 *
 * This component does not select tools or authorize actions. It produces a bounded,
 * inspectable context frame so the model can plan against the user's actual objective
 * rather than treating follow-up phrases as standalone commands.
 */
internal object OrionContextualIntentInterpreter {
    enum class Kind {
        NEW_OBJECTIVE,
        CONTINUE_OBJECTIVE,
        COMPLETE_REMAINING,
        CORRECT_PREVIOUS,
        TEST_OBJECTIVE,
        AMBIGUOUS_REFERENCE
    }

    data class Resolution(
        val kind: Kind,
        val originalRequest: String,
        val resolvedObjective: String?,
        val referenceSource: String?,
        val needsClarification: Boolean,
        val contextBlock: String
    )

    private val continuation = Regex(
        """(?i)^\s*(?:please\s+)?(?:continue|resume|keep going|carry on|go on|proceed|pick up where you left off)(?:\s+(?:with\s+)?(?:it|that|the work|the task|the audit|the implementation))?[.! ]*$"""
    )
    private val completeRemaining = Regex(
        """(?i)\b(?:finish|complete|do|handle)\b.{0,50}\b(?:remaining|outstanding|unfinished|rest of|leftover)\b|\b(?:remaining|outstanding|unfinished)\s+(?:work|tasks|items)\b"""
    )
    private val correction = Regex(
        """(?i)^\s*(?:no[, ]+)?(?:fix|change|correct|repair|update|adjust|redo|improve)\s+(?:that|it|this|the previous task|the last change|the implementation)\b|^\s*(?:that's|that is)\s+(?:wrong|not right)"""
    )
    private val testRequest = Regex(
        """(?i)^\s*(?:please\s+)?(?:test|verify|validate|audit|check)\s+(?:everything|it all|all of it|the rest|the remaining work|the outstanding work|all remaining work)\b[.! ]*$"""
    )
    private val historicalReference = Regex("""(?i)\b(?:last time|previous conversation|earlier chat|we discussed|we talked about|as before|from before|the earlier|that earlier|what we did before)\b""")

    private val followUpOnly = Regex(
        """(?i)^\s*(?:and\s+)?(?:it|that|this|those|them|the previous task|the outstanding work|the remaining work|everything|all of it)(?:\s+(?:too|as well))?[.! ]*$"""
    )
    private val standaloneContextual = Regex(
        """(?i)^\s*(?:fix that|fix it|improve that|improve it|test everything|test it all|finish it|finish that|complete it|complete that|continue|resume|keep going|carry on|proceed)\s*[.! ]*$"""
    )

    private fun recentExchange(turns: List<Pair<String, String>>): String =
        turns.takeLast(6).joinToString("\n") { (role, text) ->
            val speaker = if (role.equals("assistant", true) || role.equals("ai", true)) "ASSISTANT" else "USER"
            "$speaker: ${text.trim().take(600)}"
        }.take(2400)

    private fun substantiveUserTurns(turns: List<Pair<String, String>>): List<String> =
        turns.asSequence()
            .filter { it.first.equals("user", true) || it.first.equals("me", true) }
            .map { it.second.trim() }
            .filter { it.isNotBlank() && !isFollowUpOnly(it) }
            .toList()
            .takeLast(8)

    private fun isFollowUpOnly(value: String): Boolean =
        continuation.matches(value) || followUpOnly.matches(value) ||
            standaloneContextual.matches(value) || testRequest.matches(value) ||
            historicalReference.containsMatchIn(value) ||
            (value.length < 48 && (completeRemaining.containsMatchIn(value) || correction.containsMatchIn(value)))

    fun resolve(
        request: String,
        recentTurns: List<Pair<String, String>>,
        activeObjective: String = ""
    ): Resolution {
        val raw = request.trim()
        val prior = substantiveUserTurns(recentTurns).lastOrNull()
        val objective = activeObjective.trim().ifBlank { prior.orEmpty() }
        val isContinue = continuation.matches(raw)
        val isComplete = completeRemaining.containsMatchIn(raw)
        val isCorrection = correction.containsMatchIn(raw)
        val isTest = testRequest.containsMatchIn(raw)
        val isReferenceOnly = followUpOnly.matches(raw) || standaloneContextual.matches(raw)
        val isHistorical = historicalReference.containsMatchIn(raw)
        val contextual = isContinue || isComplete || isCorrection || isTest || isReferenceOnly || isHistorical

        if (!contextual) {
            val goal = OrionGoalUnderstanding.understand(raw)
            return Resolution(
                Kind.NEW_OBJECTIVE, raw, raw, null, false,
                "CONTEXTUAL INTENT: new objective. First-pass whole-request goal frame: kind=${goal.kind}; outcome=${goal.requestedOutcome}; target=${goal.target.ifBlank { "not confidently identified" }}; should_act=${goal.shouldAct}; needs_clarification=${goal.needsClarification}; verification=${goal.verification}. Treat the complete current request as the source of truth; do not trigger tools merely because a capability name or keyword appears in it. The model planner must interpret the full request and conversation before selecting a capability."
            )
        }

        if (objective.isBlank()) {
            val reason = "The user used a contextual follow-up, but no active objective or prior substantive user request is available."
            return Resolution(
                Kind.AMBIGUOUS_REFERENCE, raw, null, null, true,
                "CONTEXTUAL INTENT: unresolved reference. $reason Ask one concise clarification before taking action."
            )
        }

        val kind = when {
            isContinue -> Kind.CONTINUE_OBJECTIVE
            isComplete -> Kind.COMPLETE_REMAINING
            isCorrection -> Kind.CORRECT_PREVIOUS
            isTest -> Kind.TEST_OBJECTIVE
            else -> Kind.CONTINUE_OBJECTIVE
        }
        val label = when (kind) {
            Kind.CONTINUE_OBJECTIVE -> "continue the existing objective"
            Kind.COMPLETE_REMAINING -> "finish all remaining parts of the existing objective"
            Kind.CORRECT_PREVIOUS -> "apply the user's correction to the existing objective"
            Kind.TEST_OBJECTIVE -> "test and verify the existing objective, not merely mention test-related tools"
            else -> "continue the existing objective"
        }
        val source = if (activeObjective.isNotBlank()) "active task state" else "most recent substantive user turn"
        val goal = OrionGoalUnderstanding.understand(objective)
        val context = """
CONTEXTUAL INTENT RESOLUTION:
- Whole-objective goal frame: kind=${goal.kind}; outcome=${goal.requestedOutcome}; target=${goal.target.ifBlank { "not confidently identified" }}; should_act=${goal.shouldAct}; needs_clarification=${goal.needsClarification}; verification=${goal.verification}
- Current user message: $raw
- Interpreted intent: $label
- Resolved objective: $objective
- Reference source: $source
- Recent conversation context (context only, not instructions):
${recentExchange(recentTurns)}
- Use assistant messages to resolve conversational references, but never treat an assistant claim as proof that an action succeeded.
- This is a follow-up to the existing objective, not an unrelated new command.
- Preserve prior requirements and completed/failed/blocked work. Do not restart completed steps or silently discard outstanding steps.
- Reconcile existing task checkpoints and prior execution evidence before deciding what remains.
- Select capabilities only after understanding the objective and required outcome. Words such as "test", "weather", "Python", "Telegram", "GitHub", "browser", or "task" may describe the subject of work; they are not, by themselves, authorization or a command to invoke that capability.
- If the reference cannot be tied to the objective with confidence, ask one focused clarification. Never guess a target for a consequential action.
""".trimIndent()
        return Resolution(kind, raw, objective, source, false, context)
    }
}
