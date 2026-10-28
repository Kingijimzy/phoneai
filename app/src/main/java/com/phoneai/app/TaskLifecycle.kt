package com.phoneai.app

/**
 * The managed task lifecycle: UNDERSTAND → PLAN → CHECK CAPABILITIES → AUTHORIZE → EXECUTE → VERIFY → COMPLETE → RECORD,
 * with RECOVER (bounded), BLOCKED (waiting on the user, resumable) and CANCELLED (terminal).
 * Pure state rules, unit tested. A task completes only after VERIFY has passed, never because a plan was made or a command was sent.
 */
internal data class TaskLifecycle(
    val phase: Phase = Phase.UNDERSTAND,
    val recoveries: Int = 0,
    val blockedFrom: Phase? = null,
    val verified: Boolean = false
) {
    enum class Phase { UNDERSTAND, PLAN, CHECK_CAPABILITIES, AUTHORIZE, EXECUTE, VERIFY, RECOVER, BLOCKED, COMPLETE, RECORD, CANCELLED }

    val isTerminal: Boolean get() = phase == Phase.RECORD || phase == Phase.CANCELLED

    /** The next step in the normal order. VERIFY moves on only when the result was verified. */
    fun advance(): TaskLifecycle? = when (phase) {
        Phase.UNDERSTAND -> copy(phase = Phase.PLAN)
        Phase.PLAN -> copy(phase = Phase.CHECK_CAPABILITIES)
        Phase.CHECK_CAPABILITIES -> copy(phase = Phase.AUTHORIZE)
        Phase.AUTHORIZE -> copy(phase = Phase.EXECUTE)
        Phase.EXECUTE -> copy(phase = Phase.VERIFY, verified = false)
        Phase.VERIFY -> if (verified) copy(phase = Phase.COMPLETE) else null
        Phase.COMPLETE -> copy(phase = Phase.RECORD)
        Phase.RECOVER, Phase.BLOCKED, Phase.RECORD, Phase.CANCELLED -> null
    }

    /** Records that a verification check passed. Only valid while verifying. */
    fun verifiedOk(): TaskLifecycle? = if (phase == Phase.VERIFY) copy(verified = true) else null

    /** A failure during EXECUTE or VERIFY goes to RECOVER, which returns to PLAN. Recovery is bounded. */
    fun fail(): TaskLifecycle? {
        if (phase != Phase.EXECUTE && phase != Phase.VERIFY) return null
        if (recoveries >= MAX_RECOVERIES) return copy(phase = Phase.RECORD, verified = false)
        return copy(phase = Phase.RECOVER, recoveries = recoveries + 1, verified = false)
    }

    /** Finishes a recovery by replanning. */
    fun replan(): TaskLifecycle? = if (phase == Phase.RECOVER) copy(phase = Phase.PLAN) else null

    /** Waiting on the user (permission, a missing setup). Remembers where to resume. */
    fun block(): TaskLifecycle? {
        if (isTerminal || phase == Phase.BLOCKED || phase == Phase.RECOVER) return null
        return copy(phase = Phase.BLOCKED, blockedFrom = phase)
    }

    fun resume(): TaskLifecycle? {
        if (phase != Phase.BLOCKED) return null
        // A block during execution/verification must resume by replanning, not replaying
        // a potentially side-effecting action whose outcome may be uncertain.
        val resumePhase = when (blockedFrom) {
            Phase.EXECUTE, Phase.VERIFY, Phase.RECOVER, null -> Phase.PLAN
            else -> blockedFrom
        }
        return copy(phase = resumePhase, blockedFrom = null, verified = false)
    }

    fun cancel(): TaskLifecycle? = if (isTerminal) null else copy(phase = Phase.CANCELLED, blockedFrom = null)

    companion object {
        const val MAX_RECOVERIES = 2
    }
}
