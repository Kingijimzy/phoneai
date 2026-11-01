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
class OrionRemoteTaskReceiptStoreTest {
    @Test fun acceptedReceiptAndTerminalResultSurviveStoreRecreation() {
        val context = RuntimeEnvironment.getApplication()
        context.getFileStreamPath("orion_remote_receipts.json").delete()
        context.getFileStreamPath("orion_remote_receipts.json.bak").delete()
        val task = OrionTaskEnvelope.create("remote research", OrionTaskEnvelope.Kind.RESEARCH)
        assertTrue(OrionRemoteTaskReceiptStore(context).saveAccepted(task, "https://worker.example/status/123", "https://worker.example/cancel/123"))
        val reopened = OrionRemoteTaskReceiptStore(context)
        val accepted = reopened.get(task)
        assertEquals("https://worker.example/status/123", accepted?.statusUrl)
        assertTrue(reopened.saveTerminal(task, "completed", "evidence:remote"))
        val afterRestart = OrionRemoteTaskReceiptStore(context).get(task)
        assertEquals("completed", afterRestart?.terminalStatus)
        assertEquals("evidence:remote", afterRestart?.evidenceRef)
    }

    @Test fun receiptRejectsDifferentIdempotencyKeyForSameTaskId() {
        val context = RuntimeEnvironment.getApplication()
        context.getFileStreamPath("orion_remote_receipts.json").delete()
        val task = OrionTaskEnvelope.create("remote")
        val store = OrionRemoteTaskReceiptStore(context)
        assertTrue(store.saveAccepted(task, "https://worker.example/status/1", null))
        val conflicting = task.copy(idempotencyKey = "different-key")
        assertNull(store.get(conflicting))
        assertFalse(store.saveAccepted(conflicting, "https://worker.example/status/2", null))
    }
}
