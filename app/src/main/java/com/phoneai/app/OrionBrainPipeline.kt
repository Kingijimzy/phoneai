package com.phoneai.app

import android.content.Context

/**
 * Single authoritative pipeline contract for Orion intelligence (reset Rule 2).
 *
 * This is not a second agent. It is the ordered checklist every user message must pass
 * before tools run. Competing planners should eventually call into this surface only.
 *
 * Stages:
 *  understand → assemble context → retrieve memory → resolve references →
 *  plan → select tools → execute → verify → respond / recover
 */
internal object OrionBrainPipeline {

    data class Frame(
        val userMessage: String,
        val intent: OrionContextualIntentInterpreter.Resolution,
        val preAction: OrionPreActionContext.Assessment,
        val contextHints: String,
        val preferDirectAction: Boolean,
        val requiresMultiStep: Boolean
    )

    /**
     * Build the decision frame for one user message.
     * Does not call the LLM and does not execute tools.
     */
    fun prepare(
        context: Context,
        userMessage: String,
        recentTurns: List<Pair<String, String>> = emptyList(),
        activeObjective: String = ""
    ): Frame {
        val intent = OrionContextualIntentInterpreter.resolve(
            request = userMessage,
            recentTurns = recentTurns,
            activeObjective = activeObjective
        )
        val pre = OrionPreActionContext.assess(context, userMessage)
        val requiresMultiStep = OrionCommandIntentPolicy.requiresStructuredTaskPlanning(userMessage) &&
            intent.kind != OrionContextualIntentInterpreter.Kind.AMBIGUOUS_REFERENCE
        val preferDirect = !requiresMultiStep && (
            FastIntents.parse(userMessage) != null ||
                intent.kind == OrionContextualIntentInterpreter.Kind.NEW_OBJECTIVE
            )
        val hints = buildString {
            appendLine(intent.contextBlock)
            appendLine(pre.summary)
            if (intent.needsClarification) {
                appendLine("BRAIN GATE: clarification required before any tool dispatch.")
            }
            if (preferDirect) {
                appendLine("BRAIN PATH: prefer a single direct action when a capable tool matches; do not force an objective graph.")
            }
            if (requiresMultiStep) {
                appendLine("BRAIN PATH: multi-step workflow allowed; keep accurate task state and verify each step.")
            }
        }.trim()
        return Frame(
            userMessage = userMessage,
            intent = intent,
            preAction = pre,
            contextHints = hints,
            preferDirectAction = preferDirect,
            requiresMultiStep = requiresMultiStep
        )
    }

    /** Whether tools must not run yet (ambiguous reference). */
    fun blockTools(frame: Frame): Boolean = frame.intent.needsClarification
}
