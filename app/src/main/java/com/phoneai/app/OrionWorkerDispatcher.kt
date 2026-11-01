package com.phoneai.app

/**
 * Minimal provider-neutral worker seam. Remote LangGraph workers and Android-local
 * specialists implement the same typed contract; neither receives implicit phone
 * permissions from the task envelope.
 */
internal interface OrionTaskWorker {
    val workerId: String
    val supportedKinds: Set<OrionTaskEnvelope.Kind>
    val supportedCapabilities: Set<String>
    fun execute(task: OrionTaskEnvelope): OrionWorkerResult
}

internal sealed class OrionWorkerResult {
    data class Success(val evidenceRef: String) : OrionWorkerResult()
    data class NeedsOwner(val reason: String) : OrionWorkerResult()
    data class Failure(val code: String, val retryable: Boolean) : OrionWorkerResult()
}

internal sealed class OrionDispatchResult {
    data class Completed(val taskId: String, val evidenceRef: String, val fencingToken: Long) : OrionDispatchResult()
    data class Blocked(val reason: String) : OrionDispatchResult()
    data class NeedsOwner(val reason: String) : OrionDispatchResult()
    data class Failed(val code: String, val retryable: Boolean) : OrionDispatchResult()
    data class OutcomeUnknown(val reason: String) : OrionDispatchResult()
}

/**
 * Single-process dispatcher used as the reference implementation for the future
 * remote transport. Capability matching is allowlist-based and completion is fenced.
 */
internal class OrionWorkerDispatcher(
    private val leases: OrionWorkerLeaseStore,
    private val leaseDurationMs: Long = 60_000L
) {
    fun dispatch(task: OrionTaskEnvelope, worker: OrionTaskWorker): OrionDispatchResult {
        val errors = task.validate()
        if (errors.isNotEmpty()) return OrionDispatchResult.Blocked(errors.joinToString(","))
        if (task.kind !in worker.supportedKinds) return OrionDispatchResult.Blocked("worker_kind_not_supported")
        if (!worker.supportedCapabilities.containsAll(task.requestedCapabilities)) {
            return OrionDispatchResult.Blocked("worker_missing_requested_capability")
        }
        val lease = leases.acquire(task, worker.workerId, leaseDurationMs)
            ?: return OrionDispatchResult.Blocked("task_already_leased_or_completed")
        return try {
            when (val result = worker.execute(task)) {
                is OrionWorkerResult.Success -> {
                    if (leases.complete(lease, result.evidenceRef)) {
                        OrionDispatchResult.Completed(task.taskId, result.evidenceRef, lease.fencingToken)
                    } else {
                        OrionDispatchResult.OutcomeUnknown("worker returned success but fenced completion was rejected")
                    }
                }
                is OrionWorkerResult.NeedsOwner -> {
                    leases.release(lease)
                    OrionDispatchResult.NeedsOwner(result.reason.take(300))
                }
                is OrionWorkerResult.Failure -> {
                    // Do not release a possibly side-effecting worker's lease on failure.
                    // It expires naturally; reconciliation precedes any retry.
                    OrionDispatchResult.Failed(result.code.take(100), result.retryable)
                }
            }
        } catch (_: Exception) {
            // An exception may occur after an external effect. Preserve the lease until
            // expiry and require reconciliation rather than immediately replaying.
            OrionDispatchResult.OutcomeUnknown("worker_exception_outcome_requires_reconciliation")
        }
    }
}
