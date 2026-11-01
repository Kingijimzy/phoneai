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
    private fun context(): Context = RuntimeEnvironment.getApplication<Context>().also { c ->
        c.filesDir.listFiles()?.filter { it.name.startsWith("orion_distributed") || it.name.startsWith("orion_worker_lease") || it.name == "orion_agent_checkpoint.json" || it.name == "orion_agent_checkpoint.json.bak" }?.forEach { it.delete() }
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
        assertTrue(AgentCheckpointStore.beginForTask(c, task.taskId, task.objective))
        assertTrue(AgentCheckpointStore.beginStep(c, "send_message", org.json.JSONObject().put("text", "private")))
        val result = OrionExecutionCoordinator(store, leases, c).dispatch(task.taskId, worker())
        assertTrue(result is OrionCoordinatedResult.NeedsOwner)
        assertEquals("failed", store.get(task.taskId)?.status)
        assertTrue(AgentCheckpointStore.needsRecoveryConfirmation(c))
    }

    @Test fun delegatedCheckpointIsIsolatedFromOtherTasks() {
        val c = context()
        val first = OrionTaskEnvelope.create("first task")
        val second = OrionTaskEnvelope.create("second task")
        assertTrue(AgentCheckpointStore.beginForTask(c, first.taskId, first.objective))
        assertNotNull(AgentCheckpointStore.pendingForTask(c, first.taskId))
        assertNull(AgentCheckpointStore.pendingForTask(c, second.taskId))
    }

    @Test fun remoteTransportRejectsInsecureEndpointBeforeSendingCredentials() {
        val task = OrionTaskEnvelope.create("remote")
        assertThrows(IllegalArgumentException::class.java) {
            OrionLangGraphTransport("http://127.0.0.1/graph", { "never-send" }).submit(task)
        }
    }
}
