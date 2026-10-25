package com.phoneai.app

/** Privacy-minimal facts retained for future planning; never includes arguments or raw error text. */
internal data class ActionLearningRecord(
    val tool: String,
    val outcome: String,
    val failureCategory: String = ""
)

internal object ActionLearningPolicy {
    private val rules = listOf(
        Regex("(?i)\\b(429|rate.?limit\\w*|quota( exceeded| exhausted)?|resource exhausted)\\b") to "rate_limited",
        Regex("(?i)\\b(permission denied|not permitted|accessibility service|permission required|not granted)\\b") to "permission_or_accessibility",
        Regex("(?i)\\b(timeout|timed out|offline|network|connection refused|unable to resolve|unknown host)\\b") to "network_or_timeout",
        Regex("(?i)\\b(function not found|unsupported|not supported|capability unavailable|model.*unavailable)\\b") to "unsupported_capability",
        Regex("(?i)\\b(storage|could not be saved|failed to save|write failed|disk full)\\b") to "storage_failure",
        Regex("(?i)\\b(approval required|user approval|confirmation required|cancelled by user)\\b") to "approval_required",
        Regex("(?i)\\b(invalid argument|missing required|validation failed|malformed input)\\b") to "invalid_arguments",
        Regex("(?i)\\b(not found|no matching|could not find|target missing|element missing)\\b") to "target_not_found"
    )

    /** Classifies an error, then persists only this category—not the provider text or user payload. */
    fun classifyFailure(result: String): String {
        if (!result.trimStart().startsWith("error", ignoreCase = true)) return ""
        return rules.firstOrNull { (pattern, _) -> pattern.containsMatchIn(result) }?.second ?: "other_failure"
    }

    fun label(category: String): String = when (category) {
        "rate_limited" -> "rate limit/quota; wait or use the explicitly configured backup"
        "permission_or_accessibility" -> "permission or accessibility access; check the relevant system permission"
        "network_or_timeout" -> "network/timeout; check connectivity and do not rapid-retry"
        "unsupported_capability" -> "unsupported provider/model capability; choose a supported capability, not the same request again"
        "storage_failure" -> "local storage failure; verify persistence before claiming success"
        "approval_required" -> "approval or confirmation required; ask the user rather than bypassing the gate"
        "invalid_arguments" -> "invalid/missing arguments; re-read the tool contract and current inputs"
        "target_not_found" -> "target not found; refresh screen/app state before choosing a new target"
        else -> "unclassified error; inspect fresh evidence before retrying"
    }

    /** Latest result per tool wins, so an old failure is removed by a newer success. */
    fun recentFailures(records: List<ActionLearningRecord>, limit: Int = 4): List<ActionLearningRecord> {
        if (limit <= 0) return emptyList()
        return records.asReversed()
            .distinctBy { it.tool }
            .filter { it.outcome == "error returned" }
            .take(limit.coerceAtMost(8))
    }
}
