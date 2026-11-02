package com.phoneai.app

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Stage 6 durable proactive recovery / scheduled-work fallback.
 *
 * Primary Soul observation is event-driven from the connected AccessibilityService.
 * This inexact WorkManager job is a recovery and standing-order fallback after ordinary
 * process death or reboot; Android/HyperOS may defer it for battery policy. It never
 * starts the AccessibilityService or bypasses shared action policy.
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

    /** WorkManager's periodic minimum is 15 minutes; the owner dial controls only this fallback cadence. */
    fun intervalMinutes(requestedMinutes: Int): Long =
        requestedMinutes.coerceIn(INTERVAL_MINUTES.toInt(), 120).toLong()
}

internal object OrionProactiveWork {
    private const val UNIQUE_NAME = "orion_stage6_proactive_heartbeat"

    /** Keep scheduling synchronized with the owner-controlled Core + Proactive switches. */
    fun ensureScheduled(context: Context) = refresh(context)

    fun refresh(context: Context) {
        runCatching {
            val app = context.applicationContext
            if (!AgentPolicy.enabled(app, "orion_core") || !AgentPolicy.enabled(app, "proactive")) {
                WorkManager.getInstance(app).cancelUniqueWork(UNIQUE_NAME)
                return@runCatching
            }
            val interval = OrionProactivePolicy.intervalMinutes(Prefs.watchMinutes(app))
            val flex = minOf(OrionProactivePolicy.FLEX_MINUTES, interval - 1L)
            val request = PeriodicWorkRequestBuilder<OrionProactiveWorker>(
                interval, TimeUnit.MINUTES,
                flex, TimeUnit.MINUTES
            ).addTag(UNIQUE_NAME).build()
            WorkManager.getInstance(app).enqueueUniquePeriodicWork(
                UNIQUE_NAME, ExistingPeriodicWorkPolicy.REPLACE, request
            )
        }.onFailure {
            AgentPolicy.log(
                context.applicationContext, "proactive_worker", "schedule", null,
                "periodic registration unavailable: " + it.javaClass.simpleName
            )
        }
    }
}
