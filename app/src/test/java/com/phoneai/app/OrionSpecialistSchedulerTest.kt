package com.phoneai.app

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionSpecialistSchedulerTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        OrionWorkerJobStore.simulateProcessRestartForTest(context)
        context.filesDir.resolve("orion_worker_jobs.json").delete()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @After fun tearDown() {
        runCatching { WorkManager.getInstance(context).cancelAllWork().result.get(3, TimeUnit.SECONDS) }
    }

    @Test fun enqueuesIndependentUniqueWorkForParallelSpecialists() {
        val store = OrionWorkerJobStore(context)
        val jobs = (1..3).map { store.enqueue("parent-1", "parallel $it", idempotencyKey = "parallel-$it")!! }
        jobs.forEach { assertTrue(OrionSpecialistScheduler.enqueue(context, it.id)) }
        jobs.forEach { job ->
            val info = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork("orion-specialist-${job.id}").get(3, TimeUnit.SECONDS)
            assertEquals(1, info.size)
            assertEquals(WorkInfo.State.ENQUEUED, info.single().state)
        }
    }

    @Test fun stoppingParentCancelsEveryScheduledChildWorkRequest() {
        assertTrue(AgentCheckpointStore.begin(context, "Parent objective"))
        val parentId = AgentCheckpointStore.evidenceTaskId(context)!!
        val store = OrionWorkerJobStore(context)
        val jobs = (1..2).map { store.enqueue(parentId, "child $it", idempotencyKey = "child-$it")!! }
        jobs.forEach { assertTrue(OrionSpecialistScheduler.enqueue(context, it.id)) }
        assertTrue(AgentCheckpointStore.stop(context, "owner stopped parent"))
        assertTrue(store.all().filter { it.parentTaskId == parentId }.all { it.status == OrionWorkerJobStore.Status.CANCELLED })
        jobs.forEach { job ->
            val info = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork("orion-specialist-${job.id}").get(3, TimeUnit.SECONDS)
            assertEquals(WorkInfo.State.CANCELLED, info.single().state)
        }
    }

    @Test fun cancellationStopsUniqueWorkAndCommitsLedgerFirst() {
        val store = OrionWorkerJobStore(context)
        val job = store.enqueue("parent-1", "cancel me", idempotencyKey = "cancel-me")!!
        assertTrue(OrionSpecialistScheduler.enqueue(context, job.id))
        assertTrue(OrionSpecialistScheduler.cancel(context, job.id))
        assertEquals(OrionWorkerJobStore.Status.CANCELLED, store.all().single { it.id == job.id }.status)
        val info = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("orion-specialist-${job.id}").get(3, TimeUnit.SECONDS)
        assertEquals(WorkInfo.State.CANCELLED, info.single().state)
    }
}
