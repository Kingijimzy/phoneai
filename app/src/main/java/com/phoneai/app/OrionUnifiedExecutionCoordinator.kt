package com.phoneai.app

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * The single Android execution boundary for Drone Swarm tasks and legacy delegated specialists.
 *
 * The two durable ledgers remain adapters because their result contracts differ, but all scheduling,
 * cancellation and recovery decisions pass through this coordinator. WorkManager is only the Android
 * scheduler; task policy, leases, checkpoints and specialist dispatch remain in Orion.
 */
internal object OrionUnifiedExecutionCoordinator {
    data class RecoveryReport(
        val reconciledDroneTasks: Int,
        val scheduledDroneTasks: Int,
        val scheduledLegacyJobs: Int
    )

    fun enqueueDroneTask(context: Context, task: OrionTaskEnvelope): OrionCoordinatedResult {
        val app = context.applicationContext
        val tasks = OrionDistributedTaskStore(app)
        val accepted = tasks.enqueue(task)
        if (!accepted && tasks.get(task.taskId) == null) {
            return OrionCoordinatedResult.Failed(task.taskId, "task_enqueue_rejected")
        }
        return if (scheduleDroneTask(app, task.taskId)) {
            OrionCoordinatedResult.Deferred(task.taskId, "queued_for_android_workmanager")
        } else {
            tasks.fail(task.taskId, retryable = true)
            OrionCoordinatedResult.Deferred(task.taskId, "task_persisted_scheduler_unavailable_retry_pending")
        }
    }

    fun scheduleDroneTask(context: Context, taskId: String): Boolean {
        if (taskId.isBlank()) return false
        val app = context.applicationContext
        val record = runCatching { OrionDistributedTaskStore(app).get(taskId) }.getOrNull() ?: return false
        if (record.status !in setOf("queued", "retry_wait")) return record.status == "running" || record.status == "completed"
        return enqueueWork(app, "orion-task-$taskId", OrionSpecialistWorker.KEY_TASK_ID, taskId)
    }

    fun dispatchDroneTask(context: Context, taskId: String): OrionCoordinatedResult =
        OrionSpecialistRuntime.dispatch(context.applicationContext, taskId)

    fun scheduleLegacyJob(context: Context, jobId: String): Boolean {
        if (jobId.isBlank()) return false
        val app = context.applicationContext
        val job = runCatching { OrionWorkerJobStore(app).all().firstOrNull { it.id == jobId } }.getOrNull()
            ?: return false
        if (job.status != OrionWorkerJobStore.Status.QUEUED) return job.status in setOf(
            OrionWorkerJobStore.Status.RUNNING,
            OrionWorkerJobStore.Status.RESULT_PENDING,
            OrionWorkerJobStore.Status.COMPLETED
        )
        return enqueueWork(app, "orion-specialist-$jobId", OrionSpecialistWorker.KEY_JOB_ID, jobId)
    }

    fun cancelLegacyJob(context: Context, jobId: String, reason: String = "cancelled_by_owner"): Boolean {
        if (jobId.isBlank()) return false
        val app = context.applicationContext
        val cancelled = runCatching { OrionWorkerJobStore(app).cancel(jobId, reason) }.getOrDefault(false)
        val workCancelled = runCatching {
            WorkManager.getInstance(app).cancelUniqueWork("orion-specialist-$jobId")
            true
        }.getOrDefault(false)
        return cancelled || workCancelled
    }

    fun cancelDroneTask(context: Context, taskId: String, reason: String = "cancelled_by_owner"): Boolean {
        if (taskId.isBlank()) return false
        val app = context.applicationContext
        val changed = runCatching { OrionDistributedTaskStore(app).cancel(taskId, reason) }.getOrDefault(0) > 0
        val workCancelled = runCatching {
            WorkManager.getInstance(app).cancelUniqueWork("orion-task-$taskId")
            true
        }.getOrDefault(false)
        return changed || workCancelled
    }

    /** One parent cancellation fans out to both execution adapters before WorkManager is stopped. */
    fun cancelParent(context: Context, parentTaskId: String, reason: String): Int {
        if (parentTaskId.isBlank()) return 0
        val app = context.applicationContext
        val legacy = OrionWorkerJobStore(app)
        val activeLegacy = legacy.all().filter {
            it.parentTaskId == parentTaskId && it.status in setOf(
                OrionWorkerJobStore.Status.QUEUED,
                OrionWorkerJobStore.Status.RUNNING,
                OrionWorkerJobStore.Status.RESULT_PENDING,
                OrionWorkerJobStore.Status.PAUSED
            )
        }
        val legacyChanged = legacy.cancelParent(parentTaskId, reason)
        activeLegacy.forEach { job ->
            runCatching { WorkManager.getInstance(app).cancelUniqueWork("orion-specialist-${job.id}") }
        }

        val distributed = OrionDistributedTaskStore(app)
        val activeDrone = distributed.all().filter {
            (it.envelope.parentTaskId == parentTaskId || it.envelope.rootTaskId == parentTaskId) && it.status !in setOf("completed", "failed", "cancelled")
        }
        activeDrone.forEach { record ->
            runCatching { distributed.cancel(record.envelope.taskId, reason) }
            runCatching { WorkManager.getInstance(app).cancelUniqueWork("orion-task-${record.envelope.taskId}") }
        }
        return legacyChanged + activeDrone.size
    }

    /**
     * Repairs the Drone Swarm lease/task commit window and reschedules queued work from both ledgers.
     * Unique WorkManager names make repeated recovery passes idempotent.
     */
    fun reconcileAndSchedule(context: Context): RecoveryReport {
        val app = context.applicationContext
        val distributed = OrionDistributedTaskStore(app)
        val reconciled = runCatching {
            OrionExecutionCoordinator(distributed, OrionWorkerLeaseStore(app), app).reconcile()
        }.getOrDefault(0)
        val droneScheduled = distributed.all()
            .filter { it.status in setOf("queued", "retry_wait") }
            .count { scheduleDroneTask(app, it.envelope.taskId) }
        val legacyScheduled = OrionWorkerJobStore(app).all()
            .filter { it.status == OrionWorkerJobStore.Status.QUEUED }
            .count { scheduleLegacyJob(app, it.id) }
        return RecoveryReport(reconciled, droneScheduled, legacyScheduled)
    }

    private fun enqueueWork(context: Context, uniqueName: String, key: String, value: String): Boolean = try {
        val request = OneTimeWorkRequestBuilder<OrionSpecialistWorker>()
            .setInputData(Data.Builder().putString(key, value).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, request)
        true
    } catch (_: Exception) { false }
}
