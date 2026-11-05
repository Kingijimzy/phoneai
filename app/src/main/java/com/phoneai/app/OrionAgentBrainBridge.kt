package com.phoneai.app

import android.content.Context

/**
 * Single decision surface for Agent command turns (reset Rule 2).
 * Capabilities stay in Tools; they do not decide policy.
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
            appendLine("TOOL VERIFY RULE: after every tool result, OrionToolOutcomeVerifier is binding. If accept=false, do not claim success; try alternate or explain failure to the user.")
            appendLine("RECOVERY RULE: on VERIFY reject or tool error, change approach once; if still failing, stop and ask the user.")
            appendLine("EXECUTION RULE: prefer one direct action. Use multi-step graphs only when the request clearly needs ordered dependent steps.")
            appendLine("TRUTH RULE: only report actions that have real tool receipts. Never invent progress for the chat UI.")
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
