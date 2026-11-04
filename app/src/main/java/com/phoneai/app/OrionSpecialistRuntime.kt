package com.phoneai.app

import android.content.Context

/** Single registration and dispatch point for the five built-in local specialists. */
internal object OrionSpecialistRuntime {
    fun workers(context: Context): List<OrionTaskWorker> = OrionBuiltInSpecialists(context.applicationContext).workers()

    /**
     * Explicit remote route for deployments that provide an HTTPS LangGraph endpoint and
     * an in-memory token provider. Receipt storage is always enabled; credentials never
     * enter task envelopes, logs, or the receipt ledger.
     */
    fun dispatchRemote(
        context: Context,
        taskId: String,
        endpoint: String,
        bearerToken: () -> String?
    ): OrionCoordinatedResult {
        val app = context.applicationContext
        val tasks = OrionDistributedTaskStore(app)
        val remote = OrionLangGraphRemoteWorker(
            OrionLangGraphTransport(endpoint, bearerToken),
            bearerToken,
            OrionRemoteTaskReceiptStore(app)
        )
        val worker = object : OrionTaskWorker {
            override val workerId = "orion-langgraph-remote"
            override val supportedKinds = OrionTaskEnvelope.Kind.values().toSet()
            override val supportedCapabilities = setOf("research", "coding", "diagnostics", "trading_analysis", "verification")
            override fun execute(task: OrionTaskEnvelope): OrionWorkerResult = when (
                val result = remote.execute(task) { tasks.isCancellationRequested(task.taskId) }
            ) {
                is OrionLangGraphRemoteWorker.RemoteResult -> when (result.status) {
                    "completed" -> OrionWorkerResult.Success(result.evidenceRef ?: throw IllegalStateException("remote_evidence_missing"))
                    "failed" -> OrionWorkerResult.Failure(result.detail ?: "remote_task_failed", false)
                    "cancelled", "cancel_requested" -> OrionWorkerResult.NeedsOwner(result.detail ?: "remote_task_cancelled")
                    else -> OrionWorkerResult.Failure("remote_pending_resume_from_receipt", true)
                }
                else -> OrionWorkerResult.Failure("remote_result_type_unknown", false)
            }
        }
        return OrionExecutionCoordinator(tasks, OrionWorkerLeaseStore(app), app).dispatch(taskId, worker)
    }

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
