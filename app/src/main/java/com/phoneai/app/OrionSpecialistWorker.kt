package com.phoneai.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Bounded background execution for analysis-only specialists.
 * WorkManager carries only a durable job ID; the ledger, checkpoint and manifest are reloaded
 * before provider execution. No Activity or AccessibilityService instance is retained.
 */
internal class OrionSpecialistWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val store = OrionWorkerJobStore(applicationContext)
        val existing = store.all().firstOrNull { it.id == jobId } ?: return Result.failure()

        // Result delivery and finalization are independently retryable and never re-run the model.
        if (existing.status == OrionWorkerJobStore.Status.COMPLETED) return deliverResult(existing)
        if (existing.status !in setOf(OrionWorkerJobStore.Status.QUEUED, OrionWorkerJobStore.Status.RESULT_PENDING)) return Result.success()

        val parentIsLive: (String) -> Boolean = { parentId ->
            val checkpoint = AgentCheckpointStore.pending(applicationContext)
            AgentCheckpointStore.evidenceTaskId(applicationContext) == parentId &&
                checkpoint?.optString("status") in setOf("running", "waiting_for_children") &&
                !AgentCheckpointStore.cancellationRequested(applicationContext, parentId)
        }
        val manifestId = existing.manifestId ?: run {
            store.cancel(jobId, "missing_delegation_manifest")
            return Result.failure()
        }
        val manifest = OrionAgentDelegation.restore(applicationContext, manifestId)
        if (manifest == null && existing.status == OrionWorkerJobStore.Status.RESULT_PENDING &&
            OrionAgentDelegation.isCompleted(applicationContext, manifestId)) {
            if (!store.complete(jobId, existing.result, closeParent = false)) return Result.failure()
            return deliverResult(store.all().first { it.id == jobId })
        }
        if (manifest == null) {
            store.needsReview(jobId, "delegation_manifest_not_restorable_for_recovery")
            return Result.failure()
        }
        val expectedKind = if (manifest.access == OrionAgentDelegation.Access.TASK_PLANNING)
            OrionWorkerJobStore.Kind.TASK_PLANNING else OrionWorkerJobStore.Kind.ANALYSIS
        if (manifest.parentTaskId != existing.parentTaskId ||
            manifest.access !in setOf(OrionAgentDelegation.Access.ANALYSIS_ONLY, OrionAgentDelegation.Access.TASK_PLANNING) ||
            manifest.allowedTools.isNotEmpty() || manifest.agentId !in setOf("coder", "writer", "planner") ||
            existing.kind != expectedKind || existing.conversationId.isNullOrBlank() ||
            !parentIsLive(existing.parentTaskId)) {
            store.cancel(jobId, "parent_or_background_scope_not_valid")
            OrionAgentDelegation.finish(applicationContext, manifest, "cancelled: background scope rejected", cancelled = true)
            return Result.success()
        }
        if (existing.status == OrionWorkerJobStore.Status.RESULT_PENDING) {
            if (!parentIsLive(existing.parentTaskId)) {
                store.cancel(jobId, "parent_cancelled_before_result_finalization")
                OrionAgentDelegation.finish(applicationContext, manifest, "cancelled: parent no longer active", cancelled = true)
                return Result.success()
            }
            if (!OrionAgentDelegation.finish(applicationContext, manifest, existing.result)) {
                store.needsReview(jobId, "staged_result_could_not_finish_manifest")
                return Result.failure()
            }
            if (!store.complete(jobId, existing.result, closeParent = false)) {
                store.needsReview(jobId, "staged_result_could_not_finalize_job")
                return Result.failure()
            }
            return deliverResult(store.all().first { it.id == jobId })
        }
        val agent = AgentProfiles.find(manifest.agentId) ?: run {
            store.cancel(jobId, "canonical_specialist_profile_missing")
            return Result.failure()
        }
        val claimed = store.claim(jobId, parentIsLive) ?: run {
            val latest = store.all().firstOrNull { it.id == jobId }
            return if (latest?.status == OrionWorkerJobStore.Status.QUEUED) Result.retry() else Result.success()
        }
        val nim = Nim(applicationContext, sharedBudget = CommandRequestBudget(maxRequests = 3))
        val answer = AtomicReference<String?>(null)
        val failure = AtomicReference<Throwable?>(null)
        val cancelled: () -> Boolean = {
            isStopped || AgentCheckpointStore.cancellationRequested(applicationContext, claimed.parentTaskId)
        }
        val providerThread = Thread({
            try {
                answer.set(AgentRunner.runDelegated(
                    applicationContext, agent, manifest.objective, manifest,
                    capabilityDispatch = null, cancelled = cancelled, nim = nim
                ))
            } catch (t: Throwable) {
                failure.set(t)
            }
        }, "orion-specialist-${jobId.take(8)}").apply { isDaemon = true }

        try {
            providerThread.start()
            while (providerThread.isAlive) {
                providerThread.join(250)
                if (cancelled()) {
                    nim.cancel()
                    providerThread.join(5000)
                    store.cancel(jobId, "parent_cancelled_or_worker_stopped")
                    OrionAgentDelegation.finish(applicationContext, manifest,
                        "cancelled: parent stopped or worker stopped; late output discarded", cancelled = true)
                    return Result.success()
                }
            }
        } catch (_: InterruptedException) {
            nim.cancel()
            providerThread.interrupt()
            providerThread.join(5000)
            store.needsReview(jobId, "worker_interrupted_during_provider_request")
            return Result.failure()
        }

        if (failure.get() != null) {
            store.needsReview(jobId, "provider_outcome_uncertain:${failure.get()!!.javaClass.simpleName}")
            OrionAgentDelegation.finish(applicationContext, manifest,
                "error: provider outcome is uncertain; inspect before retrying", failed = true)
            return Result.failure()
        }
        val result = answer.get().orEmpty().trim()
        if (result.isBlank() || result.startsWith("error:", ignoreCase = true)) {
            store.needsReview(jobId, "provider_returned_error_or_empty_result")
            OrionAgentDelegation.finish(applicationContext, manifest,
                result.ifBlank { "error: specialist returned an empty result" }, failed = true)
            return Result.failure()
        }
        if (cancelled()) {
            store.cancel(jobId, "parent_cancelled_after_provider_return")
            OrionAgentDelegation.finish(applicationContext, manifest, "cancelled: late result discarded", cancelled = true)
            return Result.success()
        }
        // Persist output while the manifest is still running. Recovery can finish these receipts
        // without a second provider request, even if the process dies between the two commits.
        if (!store.stageResult(jobId, result)) {
            store.needsReview(jobId, "staged_result_commit_failed_after_provider_completion")
            OrionAgentDelegation.finish(applicationContext, manifest,
                "error: result persistence failed; inspect before retrying", failed = true)
            return Result.failure()
        }
        if (!OrionAgentDelegation.finish(applicationContext, manifest, result)) {
            store.needsReview(jobId, "delegation_receipt_commit_failed")
            return Result.failure()
        }
        if (!store.complete(jobId, result, closeParent = false)) {
            store.needsReview(jobId, "job_result_commit_failed_after_provider_completion")
            return Result.failure()
        }
        val completed = store.all().firstOrNull { it.id == jobId } ?: return Result.failure()
        return deliverResult(completed)
    }

    private fun deliverResult(job: OrionWorkerJobStore.Job): Result {
        val sessionId = job.conversationId ?: return Result.failure()
        val summary = "Specialist result ready (${job.kind.name.lowercase()}): ${job.result.take(420)}"
        val event = OrionConversationRepository.get(applicationContext).recordEventForSession(
            applicationContext, sessionId, "specialist-${job.id}", "specialist_result", "completed", summary
        )
        return if (event != null) Result.success()
        else if (runAttemptCount < 4) Result.retry() else Result.failure()
    }

    companion object { const val KEY_JOB_ID = "orion_specialist_job_id" }
}

/** Unique work prevents duplicate scheduler calls from creating duplicate provider execution. */
internal object OrionSpecialistScheduler {
    fun enqueue(context: Context, jobId: String): Boolean = try {
        val request = OneTimeWorkRequestBuilder<OrionSpecialistWorker>()
            .setInputData(Data.Builder().putString(OrionSpecialistWorker.KEY_JOB_ID, jobId).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork("orion-specialist-$jobId", ExistingWorkPolicy.KEEP, request)
        true
    } catch (_: Exception) { false }
}
