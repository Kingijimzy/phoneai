package com.phoneai.app

import android.content.Context

/**
 * Call at the start of every user command turn (Ultron agency layer).
 * Locks the mission, suppresses background noise as the active goal, and
 * returns the objective string for continue/fix-that.
 */
internal object OrionAgencyBootstrap {

    data class Turn(
        val effectiveGoal: String,
        val systemExtra: String,
        val isContinue: Boolean
    )

    fun begin(context: Context, userMessage: String, recentTurns: List<Pair<String, String>>): Turn {
        val raw = userMessage.trim()
        val locked = OrionMissionLock.activeGoal(context)
        val intent = OrionContextualIntentInterpreter.resolve(raw, recentTurns, locked)

        val isContinue = intent.kind == OrionContextualIntentInterpreter.Kind.CONTINUE_OBJECTIVE ||
            intent.kind == OrionContextualIntentInterpreter.Kind.COMPLETE_REMAINING ||
            intent.kind == OrionContextualIntentInterpreter.Kind.CORRECT_PREVIOUS

        val goal = when {
            isContinue -> OrionMissionLock.resumeObjective(context, intent.resolvedObjective ?: locked)
            intent.kind == OrionContextualIntentInterpreter.Kind.AMBIGUOUS_REFERENCE -> ""
            OrionMissionLock.isBackgroundNoise(raw) && locked.isNotBlank() -> locked
            else -> raw
        }

        if (goal.isNotBlank() && !OrionMissionLock.isBackgroundNoise(goal)) {
            OrionMissionLock.setActive(context, goal)
        }

        val recoveryHint = OrionForcedRecovery.afterFailure(goal.ifBlank { raw }, "prior", "", 0)
        val extra = buildString {
            appendLine("AGENCY (Ultron-style):")
            appendLine("- Active mission: ${OrionMissionLock.activeGoal(context).ifBlank { "(none)" }}")
            appendLine("- Own this goal until done or the user cancels. Do not switch to trading/soul-watch.")
            appendLine("- On tool failure: change tool/method (e.g. ${recoveryHint.nextToolHint}). Do not only apologize.")
            appendLine("- Never invent objective ids. Unknown ids bind to the RUNNING/READY node.")
            appendLine("- Success requires a tool receipt that matches the user goal.")
            appendLine("- ACT, do not narrate. If a tool can do it, call the tool before explaining.")
            appendLine("- After every tool result, continue the same mission. Feed the result into the next decision.")
            appendLine("- On failure, switch method (web search, browser, different app). Do not stop after the first miss.")
            appendLine("- Do not replace the user mission with soul-watch, startup recovery, or trading context.")
            if (intent.contextBlock.isNotBlank()) appendLine(intent.contextBlock)
        }.trim()

        return Turn(effectiveGoal = goal.ifBlank { raw }, systemExtra = extra, isContinue = isContinue)
    }

    fun afterTool(
        context: Context,
        userGoal: String,
        tool: String,
        result: String,
        attempt: Int
    ): String? {
        val failed = result.startsWith("error", true) || result.startsWith("blocked", true) ||
            result.contains("Unknown objective", true)
        if (!failed) {
            // Soft success — leave mission active until explicit complete
            return null
        }
        val plan = OrionForcedRecovery.afterFailure(userGoal, tool, result, attempt)
        if (plan.shouldRetry && plan.nextToolHint != null) {
            return "[RECOVERY] Try next: ${plan.nextToolHint}"
        }
        return plan.userMessageIfGiveUp
    }
}
