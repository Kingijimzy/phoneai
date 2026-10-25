package com.phoneai.app

/**
 * Pure state-transition rules for checkpointed agent tasks.
 * Kept separate from Android file I/O so the safety invariants can be unit tested.
 */
internal object CheckpointTransitionPolicy {
    fun needsUserConfirmation(hasInFlightAction: Boolean): Boolean = hasInFlightAction

    fun canBeginStep(hasInFlightAction: Boolean): Boolean = !hasInFlightAction

    fun canRecordResult(
        inFlightTool: String?,
        resultTool: String,
        inFlightArgs: String?,
        resultArgs: String
    ): Boolean =
        !inFlightTool.isNullOrBlank() &&
            inFlightTool == resultTool &&
            !inFlightArgs.isNullOrBlank() &&
            inFlightArgs == resultArgs

    fun canComplete(hasInFlightAction: Boolean): Boolean = !hasInFlightAction
}
