package com.phoneai.app

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Durable recovery signal for work that was queued while Orion was connected.
 *
 * A Worker must not imitate the accessibility service or replay uncertain phone actions.
 * It wakes the live service when possible; otherwise it leaves the durable queue untouched
 * and tells the user to reopen Orion. Android/HyperOS still controls when this work runs.
 */
internal class OrionRecoveryWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {
    override fun doWork(): Result {
        val pending = runCatching { OrionRuntime(applicationContext).pendingItems() }
            .getOrElse { return Result.retry() }
        if (pending.isEmpty()) return Result.success()

        val service = PhoneAiService.instance
        if (service?.requestRuntimeDrain() == true) return Result.success()

        // Do not start an AccessibilityService or replay work from a background Worker.
        OrionRuntimeNotifier.postRecoveryPending(applicationContext, pending.first())
        return Result.success()
    }
}

internal object OrionRecoveryWork {
    private const val UNIQUE_NAME = "orion_durable_runtime_recovery"

    fun schedule(context: Context, delayMs: Long = 15_000L) {
        runCatching {
            val request = OneTimeWorkRequestBuilder<OrionRecoveryWorker>()
                .setInitialDelay(delayMs.coerceIn(15_000L, 24L * 60L * 60L * 1000L), TimeUnit.MILLISECONDS)
                .addTag(UNIQUE_NAME)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
