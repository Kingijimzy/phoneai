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
            if (!parentIsLive(existing.parentTaskId)) {
                store.cancel(jobId, "parent_cancelled_before_result_delivery")
                return Result.success()
            }
            if (!store.complete(jobId, existing.result, closeParent = false)) return Result.failure()
            return deliverResult(store.all().first { it.id == jobId })
        }
        if (manifest == null) {
            store.needsReview(jobId, "delegation_manifest_not_restorable_for_recovery")
            return Result.failure()
        }
        val expectedKind = when (manifest.access) {
            OrionAgentDelegation.Access.ANALYSIS_ONLY -> OrionWorkerJobStore.Kind.ANALYSIS
            OrionAgentDelegation.Access.TASK_PLANNING -> OrionWorkerJobStore.Kind.TASK_PLANNING
            OrionAgentDelegation.Access.READ_ONLY_RESEARCH -> OrionWorkerJobStore.Kind.READ_ONLY_RESEARCH
            OrionAgentDelegation.Access.TRADING_ANALYSIS -> OrionWorkerJobStore.Kind.TRADING_ANALYSIS
        }
        val expectedAccess = when (manifest.agentId) {
            "research" -> OrionAgentDelegation.Access.READ_ONLY_RESEARCH
            "coach" -> OrionAgentDelegation.Access.TRADING_ANALYSIS
            "planner" -> OrionAgentDelegation.Access.TASK_PLANNING
            else -> OrionAgentDelegation.Access.ANALYSIS_ONLY
        }
        val permittedTools = when (manifest.access) {
            OrionAgentDelegation.Access.READ_ONLY_RESEARCH -> setOf("live_web_search", "live_news", "bybit_public_ticker")
            OrionAgentDelegation.Access.TRADING_ANALYSIS -> setOf("bybit_public_ticker")
            OrionAgentDelegation.Access.ANALYSIS_ONLY, OrionAgentDelegation.Access.TASK_PLANNING -> emptySet()
        }
        if (manifest.parentTaskId != existing.parentTaskId ||
            manifest.access != expectedAccess || manifest.agentId !in setOf("coder", "writer", "planner", "research", "coach") ||
            !permittedTools.containsAll(manifest.allowedTools) ||
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
        val failure = AtomicReference<Exception?>(null)
        val parentCancelled: () -> Boolean = {
            AgentCheckpointStore.cancellationRequested(applicationContext, claimed.parentTaskId)
        }
        val cancelled: () -> Boolean = { isStopped || parentCancelled() }
        val providerThread = Thread({
            try {
                val capabilityDispatch: (String, JSONObject) -> String = { tool, args ->
                    if (cancelled()) "error: parent or worker cancellation requested before public data lookup."
                    else when (tool) {
                        "live_web_search" -> LiveWebSearch.search(args.optString("query").take(160))
                        "live_news" -> LiveNews.search(args.optString("query").take(120))
                        "bybit_public_ticker" -> {
                            val symbol = Bybit.symbolOf(args.optString("symbol"))
                                ?: return@Thread "error: invalid public market symbol."
                            val price = Bybit.price(applicationContext, symbol)
                            "PUBLIC BYBIT LINEAR TICKER (mainnet, unauthenticated): $symbol last price=$price. No account or position data was accessed."
                        }
                        else -> "error: background worker does not implement public capability '$tool'."
                    }
                }
                answer.set(AgentRunner.runDelegated(
                    applicationContext, agent, manifest.objective, manifest,
                    capabilityDispatch = capabilityDispatch, cancelled = cancelled, nim = nim
                ))
            } catch (t: Exception) {
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
                    if (parentCancelled()) {
                        store.cancel(jobId, "parent_cancelled_during_provider_request")
                        OrionAgentDelegation.finish(applicationContext, manifest,
                            "cancelled: parent stopped; late output discarded", cancelled = true)
                        return Result.success()
                    }
                    store.needsReview(jobId, "workmanager_stopped_during_provider_request")
                    OrionAgentDelegation.finish(applicationContext, manifest,
                        "error: Android stopped the worker during a provider request; inspect before retrying", failed = true)
                    return Result.failure()
                }
            }
        } catch (_: InterruptedException) {
            nim.cancel()
            providerThread.interrupt()
            providerThread.join(5000)
            if (parentCancelled()) {
                store.cancel(jobId, "parent_cancelled_during_interrupted_request")
                OrionAgentDelegation.finish(applicationContext, manifest, "cancelled: parent stopped", cancelled = true)
                return Result.success()
            }
            store.needsReview(jobId, "worker_interrupted_during_provider_request")
            OrionAgentDelegation.finish(applicationContext, manifest,
                "error: worker interruption left provider outcome uncertain", failed = true)
            return Result.failure()
        }

        if (parentCancelled()) {
            nim.cancel()
            store.cancel(jobId, "parent_cancelled_before_result_commit")
            OrionAgentDelegation.finish(applicationContext, manifest, "cancelled: late result discarded", cancelled = true)
            return Result.success()
        }
        if (isStopped) {
            store.needsReview(jobId, "workmanager_stopped_before_result_commit")
            OrionAgentDelegation.finish(applicationContext, manifest,
                "error: Android stopped the worker before a durable result was committed", failed = true)
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
        if (parentCancelled()) {
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
        if (parentCancelled()) {
            store.cancel(jobId, "parent_cancelled_after_result_staging")
            OrionAgentDelegation.finish(applicationContext, manifest, "cancelled: staged result discarded after parent stop", cancelled = true)
            return Result.success()
        }
        if (isStopped) return Result.retry()
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
        val summary = "Specialist result ready (job ${job.id}, ${job.kind.name.lowercase()}): ${job.result.take(380)}"
        val event = OrionConversationRepository.get(applicationContext).recordEventForSession(
            applicationContext, sessionId, "specialist-${job.id}", "specialist_result", "completed", summary
        )
        return if (event != null) {
            OrionRuntimeNotifier.postSpecialistResult(applicationContext, job.id)
            Result.success()
        } else if (runAttemptCount < 4) Result.retry() else Result.failure()
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
