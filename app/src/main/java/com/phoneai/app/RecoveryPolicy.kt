package com.phoneai.app

/**
 * Risk-aware recovery for a failed tool step. Pure rules; Agent.kt applies the decision.
 *
 * - READ_ONLY: transient and timed-out failures are retried (a read has no side effect), up to [MAX_READ_RETRIES], with backoff. Other failures replan.
 * - DEV_COMMAND (shell): a failing command is diagnosed and the next action must change (replan). Never retried unchanged.
 * - WRITE: a failure that proves nothing was applied (target missing, refused before dispatch) replans.
 *   A timeout or partial result is ambiguous, so it pauses for the user rather than repeating a possible side effect.
 * - CONSEQUENTIAL (messages, deletes, security, purchases, payments): always pauses. Recovery never bypasses confirmation.
 * - PERMISSION: always pauses with the exact permission needed. Recovery never grants access.
 */
internal object RecoveryPolicy {
    enum class Risk { READ_ONLY, DEV_COMMAND, WRITE, CONSEQUENTIAL }
    enum class Failure { TRANSIENT, AMBIGUOUS, PERMISSION, TARGET_MISSING, DIAGNOSTIC, OTHER }
    enum class Decision { RETRY_READ, REPLAN, PAUSE_ASK }

    const val MAX_READ_RETRIES = 2

    private val permissionWords = listOf("permission", "not granted", "denied", "not allowed", "requires", "consent", "approve")
    private val ambiguousWords = listOf("timed out", "timeout", "no response", "interrupted", "connection lost", "may have")
    private val transientWords = listOf("temporar", "unavailable", "connection reset", "network", "503", "429", "try again", "rate limit")
    private val missingWords = listOf("not found", "no element", "not visible", "no suitable", "no match", "could not find")
    private val diagnosticWords = listOf("exit code", "exit status", "failed", "failure", "assert", "test failed")

    fun riskOf(tool: String, access: ToolAccess, consequential: Boolean): Risk = when {
        consequential -> Risk.CONSEQUENTIAL
        access == ToolAccess.READ -> Risk.READ_ONLY
        tool == "shell" -> Risk.DEV_COMMAND
        else -> Risk.WRITE
    }

    /** Order matters: permission and ambiguity are checked before the weaker transient and diagnostic signals. */
    fun classify(result: String): Failure {
        val t = result.lowercase()
        return when {
            permissionWords.any { t.contains(it) } -> Failure.PERMISSION
            ambiguousWords.any { t.contains(it) } -> Failure.AMBIGUOUS
            transientWords.any { t.contains(it) } -> Failure.TRANSIENT
            missingWords.any { t.contains(it) } -> Failure.TARGET_MISSING
            diagnosticWords.any { t.contains(it) } -> Failure.DIAGNOSTIC
            else -> Failure.OTHER
        }
    }

    fun decide(risk: Risk, failure: Failure, readRetriesUsed: Int, maxReadRetries: Int = MAX_READ_RETRIES): Decision = when {
        failure == Failure.PERMISSION -> Decision.PAUSE_ASK
        risk == Risk.CONSEQUENTIAL -> Decision.PAUSE_ASK
        failure == Failure.AMBIGUOUS && risk != Risk.READ_ONLY -> Decision.PAUSE_ASK
        risk == Risk.READ_ONLY && (failure == Failure.TRANSIENT || failure == Failure.AMBIGUOUS) ->
            if (readRetriesUsed < maxReadRetries.coerceIn(0, 4)) Decision.RETRY_READ else Decision.REPLAN
        risk == Risk.READ_ONLY -> Decision.REPLAN
        risk == Risk.DEV_COMMAND -> Decision.REPLAN
        failure == Failure.TARGET_MISSING -> Decision.REPLAN
        else -> Decision.PAUSE_ASK
    }

    /** A retry is allowed only while the task is live and within both the tool-call and time budgets. */
    fun canRetryRead(stopped: Boolean, budgetExhausted: Boolean, readRetriesUsed: Int, maxReadRetries: Int = MAX_READ_RETRIES): Boolean =
        !stopped && !budgetExhausted && readRetriesUsed < maxReadRetries.coerceIn(0, 4)

    /** Backoff before read retry number [attempt] (1-based): 0.8 s, then 1.6 s. */
    fun backoffMillis(attempt: Int, baseBackoffSeconds: Int = 1): Long =
        (baseBackoffSeconds.coerceIn(1, 300) * 1000L) * (1L shl (attempt - 1).coerceIn(0, 1))

    /** Evidence sent to the model before a replan. It names the failure and forbids repeating it unchanged. */
    fun replanNudge(tool: String, failure: Failure, evidence: String): String =
        "RECOVERY: the step '$tool' failed (${failure.name.lowercase()}): ${evidence.take(300)} " +
            "Do not repeat this action unchanged. Diagnose the cause from the evidence, then choose a different, " +
            "safe action or a revised plan. If the cause needs permission, a credential, or the user's judgment, say so and stop."

    /** Outcome of [retryRead]: the last result and how many retries ran. */
    data class ReadOutcome(val result: String, val retries: Int)

    /**
     * Runs a read-only step again after a transient or timed-out failure, within [MAX_READ_RETRIES] and the live budgets.
     * [attempt] performs one dispatch; it is only called again when the policy says a read may be repeated.
     * Cancellation and budget exhaustion stop further attempts immediately.
     */
    fun retryRead(
        first: String,
        isFailure: (String) -> Boolean,
        stopped: () -> Boolean,
        budgetExhausted: () -> Boolean,
        sleep: (Long) -> Unit,
        onRetry: (attempt: Int, failure: Failure) -> Unit,
        maxReadRetries: Int = MAX_READ_RETRIES,
        baseBackoffSeconds: Int = 1,
        attempt: () -> String
    ): ReadOutcome {
        var result = first
        var retries = 0
        while (isFailure(result)) {
            val failure = classify(result)
            if (decide(Risk.READ_ONLY, failure, retries, maxReadRetries) != Decision.RETRY_READ) break
            if (!canRetryRead(stopped(), budgetExhausted(), retries, maxReadRetries)) break
            retries++
            onRetry(retries, failure)
            sleep(backoffMillis(retries, baseBackoffSeconds))
            if (stopped()) break
            result = attempt()
        }
        return ReadOutcome(result, retries)
    }

    fun pauseMessage(tool: String, failure: Failure): String = when (failure) {
        Failure.PERMISSION -> "The step '$tool' needs permission or approval before it can run. Grant it, then say continue."
        Failure.AMBIGUOUS -> "The step '$tool' timed out or was interrupted, so it may already have changed something. I have not repeated it. Check the current state first, then tell me whether to continue."
        else -> "The step '$tool' failed in a way I cannot repeat safely. I paused and saved the progress. Check the current state, then tell me what to do next."
    }
}
