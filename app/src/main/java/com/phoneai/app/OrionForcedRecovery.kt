package com.phoneai.app

/**
 * Recovery hints are based on the failed capability and its observed error, not a phrase list
 * over the user's goal. The model owns whole-situation reasoning; dispatch still rechecks policy.
 */
internal object OrionForcedRecovery {

    data class Plan(
        val shouldRetry: Boolean,
        val nextToolHint: String?,
        val userMessageIfGiveUp: String?
    )

    fun afterFailure(
        userGoal: String,
        failedTool: String,
        result: String,
        attempt: Int
    ): Plan {
        if (attempt >= 2) {
            return Plan(
                shouldRetry = false,
                nextToolHint = null,
                userMessageIfGiveUp = "I tried ${attempt + 1} approaches and still could not complete: ${userGoal.take(120)}. ${result.take(160)}"
            )
        }
        val tool = failedTool.lowercase()
        val hint = when {
            tool.contains("python") ->
                "inspect the actual Python runtime and workspace state, then choose one minimal executable recovery"
            tool.contains("shell") || tool == "termux_command" ->
                "inspect the selected shell backend and runtime readiness, then choose a shorter non-destructive command"
            tool.startsWith("browser_") || tool == "open_url" ->
                "inspect the current browser state, then choose a different registered read-only retrieval capability"
            tool.contains("web") || tool == "fetch_url" || tool == "research_web" ->
                "inspect the actual network error, then choose a different registered research source"
            else ->
                "re-read the current state and choose a different registered capability that can verify the same outcome"
        }
        return Plan(true, hint, null)
    }
}
