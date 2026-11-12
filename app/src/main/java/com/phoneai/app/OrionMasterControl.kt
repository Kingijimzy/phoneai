package com.phoneai.app

import android.content.Context
import androidx.work.WorkManager

/**
 * Owner-controlled master power state for the Orion brain.
 *
 * A deliberate shutdown survives process death and app relaunch. Starting Orion is an
 * explicit owner action; Application.onCreate must never turn a shut-down brain back on.
 */
internal object OrionMasterControl {
    private const val PREFS = "orion_master_control"
    private const val KEY_ENABLED = "brain_enabled"
    private const val KEY_CHANGED_AT = "changed_at"

    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true) // Preserve existing installations on first upgrade.

    @Synchronized
    fun start(context: Context): Boolean {
        val app = context.applicationContext
        val saved = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, true)
            .putLong(KEY_CHANGED_AT, System.currentTimeMillis())
            .commit()
        if (!saved) return false
        val runtime = OrionRuntime(app)
        runtime.start()
        OrionProactiveWork.refresh(app)
        OrionDeviceHealthWork.ensureScheduled(app)
        if (runtime.pendingItems().isNotEmpty()) OrionRecoveryWork.schedule(app)
        runCatching { PhoneAiService.instance?.startOrionAfterMasterStart() }
        return true
    }

    @Synchronized
    fun shutdown(context: Context): Boolean {
        val app = context.applicationContext
        // Persist the stop first. Every worker and dispatch gate checks this before acting.
        val saved = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, false)
            .putLong(KEY_CHANGED_AT, System.currentTimeMillis())
            .commit()
        if (!saved) return false

        // Stop the central brain and every independently running Orion automation.
        OrionRuntime(app).stop()
        runCatching {
            val work = WorkManager.getInstance(app)
            work.cancelUniqueWork("orion_stage6_proactive_heartbeat")
            work.cancelUniqueWork("orion_durable_runtime_recovery")
        }
        runCatching { OrionDeviceHealthWork.ensureScheduled(app) }
        runCatching { PhoneAiService.instance?.stopOrionForMasterShutdown() }
        runCatching { TelegramListenerService.stop(app) }
        runCatching { WatchService.stop(app) }
        runCatching { OrionSocketForegroundService.stop(app) }
        // Finish an active screen recording cleanly so shutdown does not corrupt its file.
        runCatching { RecordService.instance?.stopRecording() }
        return true
    }

    fun dispatchBlockReason(context: Context): String? =
        if (isEnabled(context)) null else "Orion's master power is OFF. No tool action was dispatched. Start Orion from the Soul Engine command center first."
}
