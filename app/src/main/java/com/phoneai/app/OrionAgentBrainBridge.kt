package com.phoneai.app

import android.content.Context

/**
 * Thin bridge so Agent can call the single brain pipeline without competing planners.
 * Keeps Rule 2: one decision surface; tools remain capabilities.
 */
internal object OrionAgentBrainBridge {

    data class TurnPlan(
        val frame: OrionBrainPipeline.Frame,
        val systemExtra: String,
        val blockTools: Boolean,
        val clarification: String?
    )

    fun planTurn(
        context: Context,
        userMessage: String,
        recentTurns: List<Pair<String, String>>,
        activeObjective: String
    ): TurnPlan {
        OrionMemoryLifecycle.observeUserPreference(context, userMessage)
        val frame = OrionBrainPipeline.prepare(
            context = context,
            userMessage = userMessage,
            recentTurns = recentTurns,
            activeObjective = activeObjective
        )
        val memory = OrionMemoryLifecycle.promptBlock(context, userMessage)
        val systemExtra = buildString {
            appendLine(frame.contextHints)
            if (memory.isNotBlank()) appendLine(memory)
            appendLine("TOOL VERIFY RULE: after every tool result, treat OrionToolOutcomeVerifier as binding. If accept=false, do not claim success; try alternate or explain.")
            appendLine("EXECUTION RULE: prefer direct single actions. Use multi-step graphs only when the request clearly needs ordered dependent steps.")
        }.trim()
        val clarification = if (frame.intent.needsClarification) {
            "Which task should I continue? Describe the outcome you want."
        } else null
        return TurnPlan(
            frame = frame,
            systemExtra = systemExtra,
            blockTools = OrionBrainPipeline.blockTools(frame),
            clarification = clarification
        )
    }

    fun afterTool(
        context: Context,
        userGoal: String,
        tool: String,
        result: String
    ): OrionToolOutcomeVerifier.Verdict {
        val verdict = OrionToolOutcomeVerifier.verify(userGoal, tool, result)
        if (verdict.accept) {
            OrionMemoryLifecycle.observeToolSuccess(context, userGoal, tool, result)
        }
        return verdict
    }
}
