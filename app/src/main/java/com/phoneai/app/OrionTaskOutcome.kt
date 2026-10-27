package com.phoneai.app

/**
 * Builds a truthful final task report from the proof ledger and the completion gate.
 * It never executes actions or turns an unverified result into success.
 */
internal object OrionTaskOutcome {
    enum class Status {
        COMPLETE,
        COMPLETE_WITH_RECOVERED_FAILURES,
        BLOCKED_OR_UNVERIFIED,
        FAILED
    }

    data class Report(
        val status: Status,
        val successfulSteps: Int,
        val failedSteps: Int,
        val verifiedSteps: Int,
        val message: String
    )

    fun reconcile(
        completionAllowed: Boolean,
        proofs: List<AgentHooks.Proof>,
        failedCount: Int
    ): Report {
        val successes = proofs.count { it.success }
        val failures = proofs.count { !it.success }
        val verified = proofs.count { it.success && it.verified }
        if (!completionAllowed) {
            val status = if (successes == 0 && failures > 0) Status.FAILED else Status.BLOCKED_OR_UNVERIFIED
            return Report(
                status, successes, failures, verified,
                if (status == Status.FAILED)
                    "Task failed: no successful tool result is recorded. The goal is not confirmed."
                else
                    "Task not confirmed: available evidence does not satisfy the completion gate. Do not claim success."
            )
        }
        return if (failedCount > 0 || failures > 0) {
            Report(
                Status.COMPLETE_WITH_RECOVERED_FAILURES, successes, failures, verified,
                "Task reached its completion gate after $failedCount failed attempt(s). Some steps failed before recovery; only the final goal evidence supports completion."
            )
        } else {
            Report(
                Status.COMPLETE, successes, failures, verified,
                "Task completion gate passed with recorded action evidence."
            )
        }
    }
}
