package com.phoneai.app

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionExecutionCoordinatorTest {
    private fun context(): Context = RuntimeEnvironment.getApplication().also { c ->
        c.filesDir.listFiles()?.filter { it.name.startsWith("orion_distributed") || it.name.startsWith("orion_worker_lease") || it.name == "orion_agent_checkpoint.json" || it.name == "orion_agent_checkpoint.json.bak" || it.name == "orion_task_checkpoints" }?.forEach { it.deleteRecursively() }
        AgentCheckpointStore.clear(c)
    }
    private fun worker(result: OrionWorkerResult = OrionWorkerResult.Success("evidence:ok")) =
        object : OrionTaskWorker {
            override val workerId = "test-worker"
            override val supportedKinds = OrionTaskEnvelope.Kind.values().toSet()
            override val supportedCapabilities = setOf("research", "coding", "diagnostics", "trading_analysis", "verification")
            override fun execute(task: OrionTaskEnvelope) = result
        }

    @Test fun coordinatorClaimsHeartbeatsAndCommitsBothLedgers() {
        val c = context(); val store = OrionDistributedTaskStore(c); val leases = OrionWorkerLeaseStore(c)
        val task = OrionTaskEnvelope.create("coordinated")
        assertTrue(store.enqueue(task))
        val coordinator = OrionExecutionCoordinator(store, leases, c, leaseMs = 3_000, heartbeatMs = 500)
        val result = coordinator.dispatch(task.taskId, worker())
        assertTrue(result is OrionCoordinatedResult.Completed)
        assertEquals("completed", store.get(task.taskId)?.status)
        assertEquals("evidence:ok", leases.completedResult(task.taskId, task.idempotencyKey))
        assertTrue(coordinator.dispatch(task.taskId, worker()) is OrionCoordinatedResult.Deferred)
    }

    @Test fun workerFailureDoesNotMarkTaskCompletedOrReplayAutomatically() {
        val c = context(); val store = OrionDistributedTaskStore(c); val leases = OrionWorkerLeaseStore(c)
        val task = OrionTaskEnvelope.create("failure")
        assertTrue(store.enqueue(task))
        val result = OrionExecutionCoordinator(store, leases, c).dispatch(task.taskId,
            worker(OrionWorkerResult.Failure("uncertain_external_result", true)))
        assertTrue(result is OrionCoordinatedResult.Failed)
        assertEquals("retry_wait", store.get(task.taskId)?.status)
        assertFalse(leases.isCompleted(task.taskId, task.idempotencyKey))
    }

    @Test fun crashWindowReconciliationCompletesFromLeaseLedgerWithoutWorkerReplay() {
        val c = context(); val store = OrionDistributedTaskStore(c); val leases = OrionWorkerLeaseStore(c)
        val task = OrionTaskEnvelope.create("reconcile")
        assertTrue(store.enqueue(task)); assertTrue(store.claim(task.taskId))
        val lease = leases.acquire(task, "reconciler", 10_000)!!
        assertTrue(leases.complete(lease, "evidence:durable"))
        val coordinator = OrionExecutionCoordinator(store, leases, c)
        assertEquals(1, coordinator.reconcile())
        assertEquals("completed", store.get(task.taskId)?.status)
        assertEquals(0, coordinator.reconcile())
    }

    @Test fun unresolvedAgentActionBlocksCheckpointReplay() {
        val c = context(); val store = OrionDistributedTaskStore(c); val leases = OrionWorkerLeaseStore(c)
        val task = OrionTaskEnvelope.create("checkpoint task")
        assertTrue(store.enqueue(task))
        assertTrue(OrionTaskCheckpointStore(c).begin(task.taskId, task.objective))
        assertTrue(OrionTaskCheckpointStore(c).beginAction(task.taskId, "send_message", "a".repeat(64)))
        val result = OrionExecutionCoordinator(store, leases, c).dispatch(task.taskId, worker())
        assertTrue(result is OrionCoordinatedResult.NeedsOwner)
        assertEquals("failed", store.get(task.taskId)?.status)
        assertTrue(OrionTaskCheckpointStore(c).needsOwner(task.taskId))
    }

    @Test fun delegatedCheckpointIsIsolatedFromOtherTasks() {
        val c = context()
        val first = OrionTaskEnvelope.create("first task")
        val second = OrionTaskEnvelope.create("second task")
        assertTrue(OrionTaskCheckpointStore(c).begin(first.taskId, first.objective))
        assertNotNull(OrionTaskCheckpointStore(c).pending(first.taskId))
        assertNull(OrionTaskCheckpointStore(c).pending(second.taskId))
    }

    @Test fun remoteTransportRejectsInsecureEndpointBeforeSendingCredentials() {
        val task = OrionTaskEnvelope.create("remote")
        assertThrows(IllegalArgumentException::class.java) {
            OrionLangGraphTransport("http://127.0.0.1/graph", { "never-send" }).submit(task)
        }
    }
}
