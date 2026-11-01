package com.phoneai.app

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Stage 6 durable proactive heartbeat.
 *
 * Unlike the accessibility-service Handler tick, this periodic WorkManager job survives
 * ordinary process death and is restored by WorkManager after reboot. It is deliberately
 * inexact: Android/HyperOS owns execution timing and may defer it for battery policy.
 * The worker never starts the AccessibilityService or bypasses shared action policy.
 */
internal class OrionProactiveWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {
    override fun doWork(): Result {
        val coreEnabled = runCatching { AgentPolicy.enabled(applicationContext, "orion_core") }.getOrDefault(false)
        val proactiveEnabled = runCatching { AgentPolicy.enabled(applicationContext, "proactive") }.getOrDefault(false)
        if (!OrionProactivePolicy.shouldRun(coreEnabled, proactiveEnabled)) return Result.success()

        return runCatching {
            SoulEngineWatcher.run(applicationContext)
            Result.success()
        }.getOrElse {
            AgentPolicy.log(
                applicationContext, "proactive_worker", "watcher_tick", null,
                "bounded watcher failed: " + it.javaClass.simpleName
            )
            if (runAttemptCount < OrionProactivePolicy.MAX_RETRIES) Result.retry() else Result.failure()
        }
    }
}

internal object OrionProactivePolicy {
    const val INTERVAL_MINUTES = 15L
    const val FLEX_MINUTES = 5L
    const val MAX_RETRIES = 3

    /** Both the central brain and the separately opt-in proactive pack must be enabled. */
    fun shouldRun(coreEnabled: Boolean, proactiveEnabled: Boolean): Boolean =
        coreEnabled && proactiveEnabled
}

internal object OrionProactiveWork {
    private const val UNIQUE_NAME = "orion_stage6_proactive_heartbeat"

    /** Idempotent registration; KEEP preserves the existing periodic schedule. */
    fun ensureScheduled(context: Context) {
        runCatching {
            val request = PeriodicWorkRequestBuilder<OrionProactiveWorker>(
                OrionProactivePolicy.INTERVAL_MINUTES, TimeUnit.MINUTES,
                OrionProactivePolicy.FLEX_MINUTES, TimeUnit.MINUTES
            ).addTag(UNIQUE_NAME).build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }.onFailure {
            AgentPolicy.log(
                context.applicationContext, "proactive_worker", "schedule", null,
                "periodic registration unavailable: " + it.javaClass.simpleName
            )
        }
    }
}
