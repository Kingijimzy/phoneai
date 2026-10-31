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
        AgentCheckpointStore.clear(context)
        OrionWorkerJobStore.simulateProcessRestartForTest(context)
    }

    @Test fun separateStoreInstancesReloadSharedDurableState() {
        val first = OrionWorkerJobStore(context)
        val second = OrionWorkerJobStore(context)
        first.enqueue("parent-1", "First specialist", idempotencyKey = "first")
        second.enqueue("parent-1", "Second specialist", idempotencyKey = "second")
        assertEquals(2, first.all().size)
        assertEquals(2, second.all().size)
    }

    @Test fun separateInstancesCannotClaimMoreThanOneActiveJob() {
        val first = OrionWorkerJobStore(context)
        val second = OrionWorkerJobStore(context)
        first.enqueue("parent-1", "First specialist", idempotencyKey = "first")
        first.enqueue("parent-1", "Second specialist", idempotencyKey = "second")
        assertNotNull(second.claimNext { true })
        assertNull(first.claimNext { true })
        assertEquals(1, first.all().count { it.status == OrionWorkerJobStore.Status.RUNNING })
    }

    @Test fun brainSummaryReportsSavedJobStateWithoutClaimingQueuedWorkRan() {
        val store = OrionWorkerJobStore(context)
        store.enqueue("parent-1", "Compare two architecture options", idempotencyKey = "summary")
        val summary = store.promptSummary()
        assertTrue(summary.contains("durable specialist job queue", ignoreCase = true))
        assertTrue(summary.contains("[queued]"))
        assertTrue(summary.contains("Compare two architecture options"))
        assertFalse(summary.contains("completed"))
    }

    @Test fun ordinaryReloadInSameProcessDoesNotTreatLiveJobAsInterrupted() {
        val first = OrionWorkerJobStore(context)
        val job = first.enqueue("parent-1", "Continue a live analysis")!!
        assertNotNull(first.claimNext { true })
        val observed = OrionWorkerJobStore(context).all().single { it.id == job.id }
        assertEquals(OrionWorkerJobStore.Status.RUNNING, observed.status)
        assertTrue(first.complete(job.id, "Completed normally"))
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
        OrionWorkerJobStore.simulateProcessRestartForTest(context)
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

    @Test fun pausingParentCancelsItsQueuedSpecialistJobs() {
        assertTrue(AgentCheckpointStore.begin(context, "Parent research objective"))
        val parentId = AgentCheckpointStore.evidenceTaskId(context)!!
        val store = OrionWorkerJobStore(context)
        val job = store.enqueue(parentId, "Research supporting evidence")!!
        assertTrue(AgentCheckpointStore.pause(context))
        val saved = OrionWorkerJobStore(context).all().single { it.id == job.id }
        assertEquals(OrionWorkerJobStore.Status.CANCELLED, saved.status)
        assertEquals("parent_paused", saved.lastError)
    }

    @Test fun stoppingParentCancelsItsQueuedSpecialistJobs() {
        assertTrue(AgentCheckpointStore.begin(context, "Parent coding objective"))
        val parentId = AgentCheckpointStore.evidenceTaskId(context)!!
        val store = OrionWorkerJobStore(context)
        val job = store.enqueue(parentId, "Inspect test failures")!!
        assertTrue(AgentCheckpointStore.stop(context, "User stopped parent"))
        val saved = OrionWorkerJobStore(context).all().single { it.id == job.id }
        assertEquals(OrionWorkerJobStore.Status.CANCELLED, saved.status)
        assertEquals("parent_stopped", saved.lastError)
    }

    @Test fun jobRetainsManifestAndOriginConversationAcrossProcessRestart() {
        val store = OrionWorkerJobStore(context)
        val manifestId = "12345678-abcd-4321-abcd-1234567890ab"
        val conversationId = "conversation_123456789"
        val job = store.enqueue(
            "parent-1", "Summarise the research findings",
            idempotencyKey = "origin-bound",
            manifestId = manifestId,
            conversationId = conversationId
        )!!
        OrionWorkerJobStore.simulateProcessRestartForTest(context)
        val restored = OrionWorkerJobStore(context).all().single { it.id == job.id }
        assertEquals(manifestId, restored.manifestId)
        assertEquals(conversationId, restored.conversationId)
        assertEquals(OrionWorkerJobStore.Status.QUEUED, restored.status)
    }

    @Test fun malformedOriginReferencesAreRejected() {
        val store = OrionWorkerJobStore(context)
        assertNull(store.enqueue("parent-1", "Analysis", manifestId = "../bad"))
        assertNull(store.enqueue("parent-1", "Analysis", conversationId = "bad/id"))
        assertTrue(store.all().isEmpty())
    }

    @Test fun stagedProviderResultSurvivesRestartAndDoesNotReleaseActiveSlot() {
        val store = OrionWorkerJobStore(context)
        val job = store.enqueue("parent-1", "Produce a bounded analysis")!!
        assertNotNull(store.claim(job.id) { true })
        assertTrue(store.stageResult(job.id, "Verified specialist result"))
        OrionWorkerJobStore.simulateProcessRestartForTest(context)
        val restored = OrionWorkerJobStore(context).all().single { it.id == job.id }
        assertEquals(OrionWorkerJobStore.Status.RESULT_PENDING, restored.status)
        assertEquals("Verified specialist result", restored.result)
        assertNull(OrionWorkerJobStore(context).claimNext { true })
        assertTrue(OrionWorkerJobStore(context).complete(job.id, restored.result, closeParent = false))
        assertEquals(OrionWorkerJobStore.Status.COMPLETED,
            OrionWorkerJobStore(context).all().single { it.id == job.id }.status)
    }

    @Test fun stagedResultCanBeMovedToReviewWithoutBeingReplayed() {
        val store = OrionWorkerJobStore(context)
        val job = store.enqueue("parent-1", "Analyze safely")!!
        assertNotNull(store.claim(job.id) { true })
        assertTrue(store.stageResult(job.id, "Potentially completed response"))
        assertTrue(store.needsReview(job.id, "manifest commit uncertain"))
        assertEquals(OrionWorkerJobStore.Status.NEEDS_REVIEW,
            store.all().single { it.id == job.id }.status)
        assertFalse(store.complete(job.id, "replay must not happen"))
    }

    @Test fun exactClaimNeverClaimsAnotherQueuedJob() {
        val store = OrionWorkerJobStore(context)
        val first = store.enqueue("parent-1", "First")!!
        val second = store.enqueue("parent-1", "Second")!!
        val claimed = store.claim(second.id) { it == "parent-1" }!!
        assertEquals(second.id, claimed.id)
        assertEquals(OrionWorkerJobStore.Status.QUEUED, store.all().first { it.id == first.id }.status)
        assertEquals(OrionWorkerJobStore.Status.RUNNING, store.all().first { it.id == second.id }.status)
    }

    @Test fun backgroundCompletionCanLeaveParentForCentralBrainReview() {
        assertTrue(AgentCheckpointStore.begin(context, "Parent objective awaiting specialist review"))
        val parentId = AgentCheckpointStore.evidenceTaskId(context)!!
        val store = OrionWorkerJobStore(context)
        val job = store.enqueue(parentId, "Specialist analysis")!!
        assertNotNull(store.claim(job.id) { it == parentId })
        assertTrue(AgentCheckpointStore.complete(context))
        assertEquals("waiting_for_children", AgentCheckpointStore.pending(context)?.optString("status"))
        assertTrue(store.complete(job.id, "Evidence ready", closeParent = false))
        assertEquals("waiting_for_children", AgentCheckpointStore.pending(context)?.optString("status"))
    }

    @Test fun jobKindsCannotRepresentPhoneActionsOrTradingOrders() {
        assertEquals(setOf("ANALYSIS", "READ_ONLY_RESEARCH", "TRADING_ANALYSIS", "TASK_PLANNING"),
            OrionWorkerJobStore.Kind.values().map { it.name }.toSet())
    }
}
