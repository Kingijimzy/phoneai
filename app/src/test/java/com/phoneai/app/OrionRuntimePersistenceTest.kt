package com.phoneai.app

import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Exercises the persisted runtime queue and in-flight recovery across coordinator recreation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionRuntimePersistenceTest {
    private lateinit var context: Context
    private lateinit var stateFile: File

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        stateFile = File(context.filesDir, "orion_runtime.json")
        stateFile.delete()
        File(context.filesDir, "orion_runtime.json.tmp").delete()
        File(context.filesDir, "orion_runtime.json.bak").delete()
    }

    @After fun tearDown() {
        stateFile.delete()
        File(context.filesDir, "orion_runtime.json.tmp").delete()
    }

    @Test fun queuedWorkSurvivesCoordinatorRecreation() {
        val first = OrionRuntime(context)
        first.start()
        assertTrue(first.enqueue(OrionRuntime.WorkType.RESTORE_PENDING, "restore-active-chat", 4))
        assertEquals(1, first.snapshot().queued)

        val restored = OrionRuntime(context)
        assertEquals(OrionRuntime.State.STOPPED, restored.snapshot().state)
        assertEquals(1, restored.snapshot().queued)
        restored.start()
        val item = restored.claimNext()
        assertNotNull(item)
        assertEquals("restore-active-chat", item!!.key)
        assertTrue(restored.complete(item))
        assertEquals(OrionRuntime.State.ACTIVE, restored.snapshot().state)
        assertEquals(0, restored.snapshot().queued)
    }

    @Test fun inFlightWorkIsRequeuedAfterProcessDeath() {
        val first = OrionRuntime(context)
        first.start()
        assertTrue(first.enqueue(OrionRuntime.WorkType.CAPABILITY_RECHECK, "capability-check", 2))
        val claimed = first.claimNext()!!
        assertEquals(OrionRuntime.State.BUSY, first.snapshot().state)

        // A second coordinator simulates a new process reading the persisted state file.
        val afterDeath = OrionRuntime(context)
        assertEquals(OrionRuntime.State.STOPPED, afterDeath.snapshot().state)
        assertEquals(1, afterDeath.snapshot().queued)
        afterDeath.start()
        val recovered = afterDeath.claimNext()
        assertNotNull(recovered)
        assertEquals(claimed.id, recovered!!.id)
        assertTrue(afterDeath.complete(recovered))
        assertFalse(afterDeath.complete(recovered))
    }


    @Test fun interruptedStandingOrderIsReconciledButNeverBlindlyReplayed() {
        val first = OrionRuntime(context)
        first.start()
        assertTrue(first.enqueue(OrionRuntime.WorkType.SOUL_WATCH, "standing-order-tick", 2))
        val claimed = first.claimNext()!!
        assertEquals(OrionRuntime.WorkType.SOUL_WATCH, claimed.type)

        val recovered = OrionRuntime(context)
        val pending = recovered.pendingItems()
        assertEquals(1, pending.size)
        assertEquals(OrionRuntime.WorkType.CAPABILITY_RECHECK, pending.single().type)
        assertTrue(pending.single().key.startsWith("reconcile:"))
        assertTrue(pending.single().checkpoint.contains("do not replay external effects"))
    }

    @Test fun atomicSnapshotRemainsReadableAfterNormalRecreation() {
        val first = OrionRuntime(context)
        first.start()
        assertTrue(first.enqueue(OrionRuntime.WorkType.RESTORE_PENDING, "atomic-restore", 3))
        assertTrue(stateFile.isFile)
        val json = org.json.JSONObject(stateFile.readText())
        assertEquals(1, json.getJSONArray("queue").length())

        val restored = OrionRuntime(context)
        assertEquals("atomic-restore", restored.pendingItems().single().key)
    }


    @Test fun atomicBackupRestoresRuntimeQueueWhenBaseFileWasInterrupted() {
        val first = OrionRuntime(context)
        first.start()
        assertTrue(first.enqueue(OrionRuntime.WorkType.RESTORE_PENDING, "backup-restore", 3))
        val backup = File(stateFile.path + ".bak")
        assertTrue(stateFile.copyTo(backup, overwrite = true).exists())
        assertTrue(stateFile.delete())

        val restored = OrionRuntime(context)
        assertEquals("backup-restore", restored.pendingItems().single().key)
        assertTrue(stateFile.isFile)
    }

    @Test fun staleAcknowledgementsCannotCompleteOrRetryAnotherTask() {
        val runtime = OrionRuntime(context)
        runtime.start()
        assertTrue(runtime.enqueue(OrionRuntime.WorkType.RESTORE_PENDING, "one", 1))
        val actual = runtime.claimNext()!!
        val stale = actual.copy(id = "not-the-claimed-id", key = "phantom")

        assertFalse(runtime.complete(stale))
        assertEquals(OrionRuntime.State.BUSY, runtime.snapshot().state)
        assertFalse(runtime.fail(stale))
        assertEquals(OrionRuntime.State.BUSY, runtime.snapshot().state)

        assertTrue(runtime.complete(actual))
        assertEquals(0, runtime.snapshot().queued)
        assertTrue(runtime.enqueue(OrionRuntime.WorkType.RESTORE_PENDING, "two", 1))
        assertEquals("two", runtime.claimNext()!!.key)
    }
}
