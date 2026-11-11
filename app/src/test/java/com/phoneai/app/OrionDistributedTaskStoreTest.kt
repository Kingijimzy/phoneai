package com.phoneai.app

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionDistributedTaskStoreTest {
    private fun context(): Context = RuntimeEnvironment.getApplication().also {
        it.filesDir.listFiles()?.filter { f -> f.name.startsWith("orion_distributed_tasks") }?.forEach { f -> f.delete() }
    }

    @Test fun parentChildCancellationIsDurableAndCascades() {
        val c = context(); val store = OrionDistributedTaskStore(c)
        val parent = OrionTaskEnvelope.create("root objective")
        assertTrue(store.enqueue(parent))
        val child = store.createChild(parent.taskId, "child objective", OrionTaskEnvelope.Kind.RESEARCH)!!
        val grandchild = store.createChild(child.taskId, "nested", OrionTaskEnvelope.Kind.VERIFICATION)!!
        assertEquals(3, store.cancel(parent.taskId))
        assertTrue(store.isCancellationRequested(parent.taskId))
        assertEquals("cancelled", store.get(child.taskId)?.status)
        assertEquals("cancelled", store.get(grandchild.taskId)?.status)
        assertEquals("cancelled", OrionDistributedTaskStore(c).get(grandchild.taskId)?.status)
    }

    @Test fun runningCancellationRemainsPendingUntilLeaseAwareCoordinatorReconcilesIt() {
        val store = OrionDistributedTaskStore(context())
        val task = OrionTaskEnvelope.create("running objective")
        assertTrue(store.enqueue(task)); assertTrue(store.claim(task.taskId))
        assertEquals(1, store.cancel(task.taskId))
        assertEquals("cancel_requested", store.get(task.taskId)?.status)
        assertTrue(store.isCancellationRequested(task.taskId))
        assertFalse(store.claim(task.taskId))
        // The store alone cannot know whether a worker still owns a live lease.
        // OrionExecutionCoordinator performs the only safe terminal transition.
        assertEquals("cancel_requested", store.get(task.taskId)?.status)
    }

    @Test fun repeatedRunningCancellationCannotMarkTaskCancelledBeforeLeaseReconciliation() {
        val store = OrionDistributedTaskStore(context())
        val task = OrionTaskEnvelope.create("running task with repeated cancellation")
        assertTrue(store.enqueue(task))
        assertTrue(store.claim(task.taskId))

        assertEquals(1, store.cancel(task.taskId, "first_request"))
        assertEquals("cancel_requested", store.get(task.taskId)?.status)
        assertEquals(1, store.cancel(task.taskId, "repeated_request"))
        assertEquals("cancel_requested", store.get(task.taskId)?.status)
        assertTrue(store.isCancellationRequested(task.taskId))

        // Only lease-aware reconciliation may choose a terminal state.
        assertTrue(store.reconcileCancellation(task.taskId, needsReview = false, reason = "lease_expired_no_owner_action"))
        assertEquals("cancelled", store.get(task.taskId)?.status)
        assertEquals(0, store.cancel(task.taskId, "too_late"))
    }

    @Test fun separateStoreInstancesCannotClaimTheSameTaskConcurrently() {
        val appContext = context()
        val seed = OrionDistributedTaskStore(appContext)
        val task = OrionTaskEnvelope.create("single owner claim")
        assertTrue(seed.enqueue(task))

        val workers = 12
        val pool = Executors.newFixedThreadPool(workers)
        val gate = CountDownLatch(1)
        val done = CountDownLatch(workers)
        val claims = java.util.concurrent.atomic.AtomicInteger(0)
        repeat(workers) {
            pool.execute {
                try {
                    gate.await(3, TimeUnit.SECONDS)
                    if (OrionDistributedTaskStore(appContext).claim(task.taskId)) claims.incrementAndGet()
                } finally {
                    done.countDown()
                }
            }
        }
        gate.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(1, claims.get())
        assertEquals("running", OrionDistributedTaskStore(appContext).get(task.taskId)?.status)
    }

    @Test fun completionIsIdempotentAndTerminalStatesDoNotReopen() {
        val store = OrionDistributedTaskStore(context())
        val task = OrionTaskEnvelope.create("complete objective")
        assertTrue(store.enqueue(task)); assertTrue(store.claim(task.taskId))
        assertTrue(store.complete(task.taskId, "evidence:verified"))
        assertTrue(store.complete(task.taskId, "evidence:verified"))
        assertFalse(store.complete(task.taskId, "evidence:different"))
        assertFalse(store.claim(task.taskId))
    }

    @Test fun rejectsOrphanChildrenAndInvalidLangGraphTransport() {
        val store = OrionDistributedTaskStore(context())
        val orphan = OrionTaskEnvelope.create("orphan", parentTaskId = "missing", rootTaskId = "root")
        assertFalse(store.enqueue(orphan))
        val task = OrionTaskEnvelope.create("remote")
        assertThrows(IllegalArgumentException::class.java) {
            OrionLangGraphTransport("http://example.com/graph", { "token" }).submit(task)
        }
    }

    @Test fun specialistWorkersEnforceRequestedRoleAndReturnHandlerEvidence() {
        val worker = OrionTaskSpecialistWorker("research-1", OrionSpecialist.RESEARCH) {
            OrionWorkerResult.Success("evidence:research")
        }
        val task = OrionTaskEnvelope.create("research", OrionTaskEnvelope.Kind.RESEARCH,
            capabilities = listOf("research"))
        assertEquals(OrionWorkerResult.Success("evidence:research"), worker.execute(task))
    }
}
