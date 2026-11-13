package com.phoneai.app

import android.content.Context
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionUnifiedExecutionCoordinatorTest {
    private fun context(): Context {
        val context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(Log.ERROR).build()
        )
        listOf(
            "orion_distributed_tasks.json",
            "orion_distributed_tasks.json.bak",
            "orion_worker_jobs.json",
            "orion_worker_jobs.json.bak"
        ).forEach { File(context.filesDir, it).delete() }
        OrionWorkerJobStore.simulateProcessRestartForTest(context)
        context.getSharedPreferences("orion_master_control", Context.MODE_PRIVATE).edit()
            .putBoolean("brain_enabled", true).commit()
        return context
    }

    @Test fun masterShutdownCancelsBothQueuedAndRunningExecutionLedgers() {
        val context = context()
        val droneStore = OrionDistributedTaskStore(context)
        val droneQueued = OrionTaskEnvelope.create("queued Drone task")
        val droneRunning = OrionTaskEnvelope.create("running Drone task")
        assertTrue(droneStore.enqueue(droneQueued))
        assertTrue(droneStore.enqueue(droneRunning))
        assertTrue(droneStore.claim(droneRunning.taskId))

        val legacyStore = OrionWorkerJobStore(context)
        val legacyQueued = legacyStore.enqueue("parent-queued", "queued specialist", idempotencyKey = "queued")!!
        val legacyRunning = legacyStore.enqueue("parent-running", "running specialist", idempotencyKey = "running")!!
        assertNotNull(legacyStore.claim(legacyRunning.id) { true })

        assertTrue(OrionUnifiedExecutionCoordinator.scheduleDroneTask(context, droneQueued.taskId))
        assertTrue(OrionUnifiedExecutionCoordinator.scheduleLegacyJob(context, legacyQueued.id))

        val changed = OrionUnifiedExecutionCoordinator.cancelAll(context, "test_master_shutdown")

        assertEquals(4, changed)
        assertEquals("cancelled", droneStore.get(droneQueued.taskId)?.status)
        assertEquals("cancel_requested", droneStore.get(droneRunning.taskId)?.status)
        assertEquals(OrionWorkerJobStore.Status.CANCELLED,
            legacyStore.all().first { it.id == legacyQueued.id }.status)
        assertEquals(OrionWorkerJobStore.Status.CANCEL_REQUESTED,
            legacyStore.all().first { it.id == legacyRunning.id }.status)

        val work = WorkManager.getInstance(context)
        assertEquals(WorkInfo.State.CANCELLED,
            work.getWorkInfosForUniqueWork("orion-task-${droneQueued.taskId}").get().single().state)
        assertEquals(WorkInfo.State.CANCELLED,
            work.getWorkInfosForUniqueWork("orion-specialist-${legacyQueued.id}").get().single().state)
    }
}
