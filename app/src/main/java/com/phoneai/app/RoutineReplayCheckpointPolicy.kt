package com.phoneai.app

/** Pure invariants for the persisted routine replay cursor. */
internal object RoutineReplayCheckpointPolicy {
    const val MAX_STEPS = 100

    fun isValid(routine: String, totalSteps: Int, nextStep: Int, inFlightStep: Int): Boolean =
        routine.isNotBlank() &&
            totalSteps in 1..MAX_STEPS &&
            nextStep in 0..totalSteps &&
            inFlightStep in -1 until totalSteps &&
            (inFlightStep == -1 || inFlightStep == nextStep)

    fun canBegin(hasCheckpoint: Boolean, routine: String, totalSteps: Int): Boolean =
        !hasCheckpoint && routine.isNotBlank() && totalSteps in 1..MAX_STEPS

    fun canMarkInFlight(
        snapshot: RoutineReplayCheckpointStore.Snapshot?,
        routine: String,
        totalSteps: Int,
        index: Int
    ): Boolean =
        snapshot != null &&
            snapshot.routine == routine &&
            snapshot.totalSteps == totalSteps &&
            index in 0 until totalSteps &&
            snapshot.nextStep == index &&
            snapshot.inFlightStep == -1

    fun canMarkCompleted(snapshot: RoutineReplayCheckpointStore.Snapshot?, routine: String, index: Int): Boolean =
        snapshot != null && snapshot.routine == routine && snapshot.inFlightStep == index

    fun canPause(snapshot: RoutineReplayCheckpointStore.Snapshot?, routine: String, index: Int): Boolean =
        snapshot != null && snapshot.routine == routine && index in 0..snapshot.totalSteps
}
