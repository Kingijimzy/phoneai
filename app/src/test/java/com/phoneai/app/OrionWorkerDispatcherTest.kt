package com.phoneai.app

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionWorkerDispatcherTest {
    private lateinit var context: Context
    private var now = System.currentTimeMillis()
    private lateinit var dispatcher: OrionWorkerDispatcher

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getFileStreamPath("orion_worker_leases.json").delete()
        context.getFileStreamPath("orion_worker_leases.json.bak").delete()
        dispatcher = OrionWorkerDispatcher(OrionWorkerLeaseStore(context) { now }, 5_000)
    }

    private fun task(capabilities: List<String> = listOf("research_web")) =
        OrionTaskEnvelope.create("Research a public fact", kind = OrionTaskEnvelope.Kind.RESEARCH,
            capabilities = capabilities, now = now)

    private fun worker(result: OrionWorkerResult = OrionWorkerResult.Success("evidence:verified"),
                       capabilities: Set<String> = setOf("research_web"),
                       action: (OrionTaskEnvelope) -> OrionWorkerResult = { result }) =
        object : OrionTaskWorker {
            override val workerId = "local-research-worker"
            override val supportedKinds = setOf(OrionTaskEnvelope.Kind.RESEARCH)
            override val supportedCapabilities = capabilities
            override fun execute(task: OrionTaskEnvelope) = action(task)
        }

    @Test fun successfulWorkerProducesFencedCompletionAndEvidenceReference() {
        val task = task()
        val result = dispatcher.dispatch(task, worker()) as OrionDispatchResult.Completed
        assertEquals(task.taskId, result.taskId)
        assertEquals("evidence:verified", result.evidenceRef)
        assertTrue(result.fencingToken > 0)
    }

    @Test fun unsupportedKindOrCapabilityNeverExecutesWorker() {
        var called = false
        val noCapability = worker(capabilities = emptySet()) { called = true; OrionWorkerResult.Success("evidence:x") }
        assertTrue(dispatcher.dispatch(task(), noCapability) is OrionDispatchResult.Blocked)
        assertFalse(called)
    }

    @Test fun ownerGateIsReturnedWithoutMarkingTaskCompleted() {
        val task = task(emptyList())
        val result = dispatcher.dispatch(task, worker(OrionWorkerResult.NeedsOwner("approval required")))
        assertTrue(result is OrionDispatchResult.NeedsOwner)
    }

    @Test fun thrownWorkerExceptionIsNotAutomaticallyReplayed() {
        val task = task(emptyList())
        val worker = worker(capabilities = emptySet()) { throw IllegalStateException("interrupted") }
        val result = dispatcher.dispatch(task, worker)
        assertTrue(result is OrionDispatchResult.OutcomeUnknown)
        assertFalse(OrionWorkerLeaseStore(context) { now }.isCompleted(task.taskId, task.idempotencyKey))
    }
}
