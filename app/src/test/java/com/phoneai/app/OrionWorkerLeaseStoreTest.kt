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
class OrionWorkerLeaseStoreTest {
    private lateinit var context: Context
    private var now = 1_800_000_000_000L
    private lateinit var store: OrionWorkerLeaseStore

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getFileStreamPath("orion_worker_leases.json").delete()
        context.getFileStreamPath("orion_worker_leases.json.bak").delete()
        store = OrionWorkerLeaseStore(context) { now }
    }

    private fun task() = OrionTaskEnvelope.create("Research market structure", now = now)

    @Test fun activeLeaseExcludesSecondWorkerAndHeartbeatExtendsIt() {
        val task = task()
        val first = store.acquire(task, "worker-a", 5_000)!!
        assertNull(store.acquire(task, "worker-b", 5_000))
        val renewed = store.heartbeat(first, 10_000)!!
        now += 6_000
        assertFalse(store.complete(first, "evidence:one"))
        assertTrue(store.complete(renewed, "evidence:one"))
    }

    @Test fun expiredLeaseCanBeReacquiredButOldFencingTokenCannotComplete() {
        val task = task()
        val old = store.acquire(task, "worker-a", 2_000)!!
        now += 2_001
        val newer = store.acquire(task, "worker-b", 5_000)!!
        assertTrue(newer.fencingToken > old.fencingToken)
        assertFalse(store.complete(old, "evidence:stale"))
        assertTrue(store.complete(newer, "evidence:current"))
    }

    @Test fun completionIsIdempotentForSameResultAndRejectsDifferentResult() {
        val task = task()
        val lease = store.acquire(task, "worker-a", 5_000)!!
        assertTrue(store.complete(lease, "evidence:result"))
        assertTrue(store.complete(lease, "evidence:result"))
        assertFalse(store.complete(lease, "evidence:other"))
        assertTrue(store.isCompleted(task.taskId, task.idempotencyKey))
    }

    @Test fun rejectsInvalidWorkerLeaseDurationAndResultReference() {
        val task = task()
        assertNull(store.acquire(task, "bad worker id", 5_000))
        assertNull(store.acquire(task, "worker-a", 10))
        val lease = store.acquire(task, "worker-a", 5_000)!!
        assertFalse(store.complete(lease, "../../secret"))
    }

    @Test fun releaseAllowsSafeReassignmentWithHigherFence() {
        val task = task()
        val first = store.acquire(task, "worker-a", 5_000)!!
        assertTrue(store.release(first))
        val second = store.acquire(task, "worker-b", 5_000)!!
        assertTrue(second.fencingToken > first.fencingToken)
    }
}
