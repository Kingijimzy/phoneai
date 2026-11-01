package com.phoneai.app

import android.content.Context

/** Single registration and dispatch point for the five built-in local specialists. */
internal object OrionSpecialistRuntime {
    fun workers(context: Context): List<OrionTaskWorker> = OrionBuiltInSpecialists(context.applicationContext).workers()

    fun dispatch(context: Context, taskId: String): OrionCoordinatedResult {
        val app = context.applicationContext
        val tasks = OrionDistributedTaskStore(app)
        val task = tasks.get(taskId)?.envelope
            ?: return OrionCoordinatedResult.Failed(taskId, "task_not_found")
        val workers = workers(app)
        val requested = task.requestedCapabilities.map { it.lowercase() }.toSet()
        val selected = when {
            "coding" in requested || task.kind == OrionTaskEnvelope.Kind.CODE -> OrionSpecialist.CODING
            "diagnostics" in requested || task.kind == OrionTaskEnvelope.Kind.DIAGNOSTICS -> OrionSpecialist.DIAGNOSTICS
            "trading_analysis" in requested || task.kind == OrionTaskEnvelope.Kind.TRADING_ANALYSIS -> OrionSpecialist.TRADING_ANALYSIS
            "verification" in requested || task.kind == OrionTaskEnvelope.Kind.VERIFICATION -> OrionSpecialist.VERIFICATION
            "research" in requested || task.kind == OrionTaskEnvelope.Kind.RESEARCH -> OrionSpecialist.RESEARCH
            else -> return OrionCoordinatedResult.NeedsOwner(taskId, "specialist_route_ambiguous; add one requested capability")
        }
        val worker = workers.firstOrNull { it.workerId == when (selected) {
            OrionSpecialist.RESEARCH -> "orion-research"
            OrionSpecialist.CODING -> "orion-coding"
            OrionSpecialist.DIAGNOSTICS -> "orion-diagnostics"
            OrionSpecialist.TRADING_ANALYSIS -> "orion-trading"
            OrionSpecialist.VERIFICATION -> "orion-verification"
        } } ?: return OrionCoordinatedResult.Failed(taskId, "specialist_not_registered")
        return OrionExecutionCoordinator(tasks, OrionWorkerLeaseStore(app), app).dispatch(taskId, worker)
    }
}
