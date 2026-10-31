package com.phoneai.app

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionWorkerJobStoreTest {
    private lateinit var context: Context
    private lateinit var file: File

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        file = File(context.filesDir, "orion_worker_jobs.json")
        file.delete()
        File(file.path + ".bak").delete()
        File(file.path + ".tmp").delete()
    }

    @Test fun jobsPersistAndDuplicateIdempotencyKeyReturnsOriginal() {
        val store = OrionWorkerJobStore(context)
        val first = store.enqueue("parent-1", "Research Android background workers", idempotencyKey = "research-1")!!
        val duplicate = store.enqueue("parent-1", "Different wording", idempotencyKey = "research-1")!!
        assertEquals(first.id, duplicate.id)
        assertEquals(1, OrionWorkerJobStore(context).all().size)
    }

    @Test fun claimRequiresExactLiveParentAndOnlyOneJobRunsAtATime() {
        val store = OrionWorkerJobStore(context)
        store.enqueue("parent-1", "Research first", idempotencyKey = "one")
        store.enqueue("parent-1", "Research second", idempotencyKey = "two")
        val claimed = store.claimNext { it == "parent-1" }!!
        assertEquals(OrionWorkerJobStore.Status.RUNNING, claimed.status)
        assertNull(store.claimNext { true })
        assertTrue(store.complete(claimed.id, "Verified findings"))
        assertNotNull(store.claimNext { it == "parent-1" })
    }

    @Test fun missingOrPausedParentCancelsQueuedJobFailClosed() {
        val store = OrionWorkerJobStore(context)
        val job = store.enqueue("missing-parent", "Do not run orphaned work")!!
        assertNull(store.claimNext { false })
        val saved = store.all().single { it.id == job.id }
        assertEquals(OrionWorkerJobStore.Status.CANCELLED, saved.status)
        assertEquals("parent_not_running_or_unverifiable", saved.lastError)
    }

    @Test fun parentCancellationCancelsAllQueuedAndRunningChildren() {
        val store = OrionWorkerJobStore(context)
        val running = store.enqueue("parent-1", "Research active")!!
        val queued = store.enqueue("parent-1", "Research queued")!!
        assertNotNull(store.claimNext { true })
        assertEquals(2, store.cancelParent("parent-1"))
        assertTrue(store.all().all { it.status == OrionWorkerJobStore.Status.CANCELLED })
        assertFalse(store.complete(running.id, "late result"))
        assertFalse(store.cancel(queued.id))
    }

    @Test fun processDeathMovesRunningJobToNeedsReviewInsteadOfReplayingProviderCall() {
        val store = OrionWorkerJobStore(context)
        val job = store.enqueue("parent-1", "Analyze market structure")!!
        assertNotNull(store.claimNext { true })
        val restored = OrionWorkerJobStore(context).all().single { it.id == job.id }
        assertEquals(OrionWorkerJobStore.Status.NEEDS_REVIEW, restored.status)
        assertTrue(restored.lastError.contains("must_be_reviewed"))
        assertNull(OrionWorkerJobStore(context).claimNext { true })
    }

    @Test fun completionCannotBeAppliedTwiceOrToUnclaimedJob() {
        val store = OrionWorkerJobStore(context)
        val job = store.enqueue("parent-1", "Analyze")!!
        assertFalse(store.complete(job.id, "not started"))
        assertNotNull(store.claimNext { true })
        assertTrue(store.complete(job.id, "done"))
        assertFalse(store.complete(job.id, "duplicate completion"))
    }

    @Test fun jobKindsCannotRepresentPhoneActionsOrTradingOrders() {
        assertEquals(setOf("ANALYSIS", "READ_ONLY_RESEARCH", "TRADING_ANALYSIS", "TASK_PLANNING"),
            OrionWorkerJobStore.Kind.values().map { it.name }.toSet())
    }
}
