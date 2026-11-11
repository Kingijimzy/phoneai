package com.phoneai.app

import android.content.Context

/**
 * Call after semantic objective resolution (Ultron agency layer).
 * Locks the mission, suppresses background noise as the active goal, and
 * reconciles the model-selected objective with durable mission state.
 */
internal object OrionAgencyBootstrap {

    data class Turn(
        val effectiveGoal: String,
        val systemExtra: String,
        val isContinue: Boolean
    )

    fun begin(context: Context, userMessage: String, recentTurns: List<Pair<String, String>>): Turn {
        // The upstream semantic router supplies a complete objective. This layer owns continuity,
        // not natural-language classification: it reconciles the objective with the durable mission.
        val raw = userMessage.trim()
        val locked = OrionMissionLock.activeGoal(context)
        val sameMission = locked.isNotBlank() &&
            (locked.equals(raw, ignoreCase = true) || MemoryRanking.similarity(locked, raw) >= 0.72)
        val goal = raw
        if (goal.isNotBlank()) OrionMissionLock.setActive(context, goal)

        val recoveryHint = OrionForcedRecovery.afterFailure(goal.ifBlank { locked }, "prior", "", 0)
        val extra = buildString {
            appendLine("AGENCY (GRASP mission continuity):")
            appendLine("- Model-resolved objective: ${goal.ifBlank { "(none)" }}")
            appendLine("- Previously active mission: ${locked.ifBlank { "(none)" }}")
            appendLine("- Own the current objective until verified complete, genuinely blocked, cancelled, or an owner decision is required.")
            appendLine("- On tool failure, inspect the result and choose a materially different supported route (e.g. ${recoveryHint.nextToolHint}).")
            appendLine("- Never invent objective ids. Unknown ids bind to the RUNNING/READY node.")
            appendLine("- Success requires observable evidence that matches the intended outcome.")
            appendLine("- After every tool result, update the belief state and continue the same objective where appropriate.")
            appendLine("- Do not replace the current mission with background Soul Watch or unrelated trading context.")
        }.trim()

        return Turn(effectiveGoal = goal.ifBlank { locked }, systemExtra = extra, isContinue = sameMission)
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
