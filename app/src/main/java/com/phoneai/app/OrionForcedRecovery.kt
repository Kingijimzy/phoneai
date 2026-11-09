package com.phoneai.app

/**
 * Ultron-style: tool failure must change method, not end with an essay.
 * Pure policy — Agent applies the suggested next tool.
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
        val g = userGoal.lowercase()
        val t = failedTool.lowercase()

        // Sports / scores
        if (t.contains("sport") || t.contains("livescore") || t.contains("live_score") ||
            Regex("\\b(score|match|fixture|atletico|barca|barcelona)\\b").containsMatchIn(g)
        ) {
            if (attempt == 0) return Plan(true, "web_search or research_web for the match score with sources", null)
            return Plan(true, "open_url news source and extract scoreline", null)
        }

        // Site scrape / APKVision / file size questions
        if (g.contains("apkvision") || g.contains("scraper") || g.contains("file size") ||
            t.contains("python") || t.contains("browser")
        ) {
            if (attempt == 0) return Plan(true, "browser_open + read page text for the answer", null)
            return Plan(true, "web_search site-specific query then cite", null)
        }

        // Open music
        if (Regex("\\b(open|launch).*(music|spotify)\\b").containsMatchIn(g)) {
            return Plan(true, "open_app with installed music package (not web YouTube)", null)
        }

        // Generic
        val alt = when {
            t.contains("python") -> "workspace_write then simplified python_exec"
            t.contains("shell") -> "retry shorter shell command"
            t.contains("browser") || t == "open_url" -> "web_search as fallback"
            else -> "web_search or browser for the same user goal"
        }
        return Plan(true, alt, null)
    }
}
