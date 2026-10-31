package com.phoneai.app

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** A reminder runs only after the owner explicitly grants the routine-reminder scope and schedules it. */
internal class OrionRoutineReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        if (!OrionSensorConsent.read(applicationContext, OrionSensorScope.ROUTINE_REMINDERS).active()) return Result.success()
        val memoryId = inputData.getInt(KEY_MEMORY_ID, -1)
        val memory = MemoryStore.all(applicationContext).firstOrNull { it.id == memoryId } ?: return Result.success()
        if (!OrionRoutinePolicy.eligible(applicationContext, memory)) return Result.success()
        OrionRuntimeNotifier.postRoutineReminder(applicationContext, memory.text)
        return Result.success()
    }

    companion object {
        const val KEY_MEMORY_ID = "routine_memory_id"

        /** whenMillis must be chosen by the owner; Orion never guesses a reminder time from prose. */
        fun schedule(context: Context, memoryId: Int, whenMillis: Long): Boolean {
            val grant = OrionSensorConsent.read(context, OrionSensorScope.ROUTINE_REMINDERS)
            if (!grant.active() || whenMillis <= System.currentTimeMillis()) return false
            val memory = MemoryStore.all(context).firstOrNull { it.id == memoryId } ?: return false
            if (!OrionRoutinePolicy.eligible(context, memory)) return false
            val delay = (whenMillis - System.currentTimeMillis()).coerceAtLeast(1L)
            val request = OneTimeWorkRequestBuilder<OrionRoutineReminderWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(Data.Builder().putInt(KEY_MEMORY_ID, memoryId).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "orion-routine-reminder-$memoryId", ExistingWorkPolicy.REPLACE, request
            )
            return true
        }

        fun cancel(context: Context, memoryId: Int) {
            WorkManager.getInstance(context).cancelUniqueWork("orion-routine-reminder-$memoryId")
        }
    }
}
