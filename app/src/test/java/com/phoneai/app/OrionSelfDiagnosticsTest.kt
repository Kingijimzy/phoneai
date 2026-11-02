package com.phoneai.app

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionSelfDiagnosticsTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val file = File(context.filesDir, "orion_worker_jobs.json")
        file.delete()
        File(file.path + ".bak").delete()
        File(file.path + ".tmp").delete()
        OrionWorkerJobStore.simulateProcessRestartForTest(context)
    }

    @Test fun diagnosticsCountsActualDurableQueuedWork() {
        val store = OrionWorkerJobStore(context)
        store.enqueue("parent-diagnostic", "Inspect a safe project issue", idempotencyKey = "diag-queue")!!
        val report = OrionSelfDiagnostics.inspect(context)
        assertEquals(1, report.queuedBackgroundWork)
        assertTrue(report.promptBlock().contains("queued_background_work=1"))
    }

    @Test fun diagnosticsFlagsInterruptedSpecialistWorkForParentReview() {
        val store = OrionWorkerJobStore(context)
        val job = store.enqueue("parent-diagnostic", "Review a delegated result", idempotencyKey = "diag-review")!!
        assertEquals(job.id, store.claimNext { true }?.id)
        OrionWorkerJobStore.simulateProcessRestartForTest(context)
        val report = OrionSelfDiagnostics.inspect(context)
        assertEquals(1, report.queuedBackgroundWork)
        assertTrue(report.findings.any {
            it.component == "specialist_queue" && it.title == "Specialist result needs review"
        })
    }
}
