package com.phoneai.app

/** Pure lifecycle gates for delegated task checkpoints. */
internal object OrionDelegatedTaskTransitionPolicy {
    fun canBeginAction(status: String, hasInFlight: Boolean, stopRequested: Boolean, pauseRequested: Boolean): Boolean =
        status == "running" && !hasInFlight && !stopRequested && !pauseRequested

    fun canRecordAction(status: String, stopRequested: Boolean, pauseRequested: Boolean): Boolean =
        status == "running" && !stopRequested && !pauseRequested

    fun canComplete(status: String, hasInFlight: Boolean, stopRequested: Boolean, pauseRequested: Boolean): Boolean =
        status == "running" && !hasInFlight && !stopRequested && !pauseRequested
}
