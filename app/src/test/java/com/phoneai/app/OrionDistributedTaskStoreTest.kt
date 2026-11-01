package com.phoneai.app

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

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

    @Test fun runningCancellationRequiresRecoveryAndCannotBeClaimedAgain() {
        val store = OrionDistributedTaskStore(context())
        val task = OrionTaskEnvelope.create("running objective")
        assertTrue(store.enqueue(task)); assertTrue(store.claim(task.taskId))
        assertEquals(1, store.cancel(task.taskId))
        assertEquals("cancel_requested", store.get(task.taskId)?.status)
        assertTrue(store.isCancellationRequested(task.taskId))
        assertFalse(store.claim(task.taskId))
        assertTrue(store.recoverQueued().contains(task.taskId))
        assertEquals("cancelled", store.get(task.taskId)?.status)
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
        val worker = OrionSpecialistWorker("research-1", OrionSpecialist.RESEARCH) {
            OrionWorkerResult.Success("evidence:research")
        }
        val task = OrionTaskEnvelope.create("research", OrionTaskEnvelope.Kind.RESEARCH,
            capabilities = listOf("research"))
        assertEquals(OrionWorkerResult.Success("evidence:research"), worker.execute(task))
    }
}
