package com.phoneai.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class OrionEvidenceLedgerTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun recordsDispatchWithDistinctVerifiedUnverifiedAndFailedOutcomes() {
        val task = "ledger-outcomes-${System.nanoTime()}"
        assertTrue(OrionEvidenceLedger.recordDispatch(context, task, "device", "click", JSONObject().put("id", 3), 10L,
            "clicked\nLOCAL VERIFY PASS: visible state changed"))
        assertTrue(OrionEvidenceLedger.recordDispatch(context, task, "shell", "shell", JSONObject().put("command", "pwd"), 20L, "/workspace"))
        assertTrue(OrionEvidenceLedger.recordDispatch(context, task, "mcp", "mcp_call", JSONObject(), 30L, "error: offline"))
        val rows = OrionEvidenceLedger.events(context, task)
        assertEquals(listOf(OrionEvidenceLedger.Outcome.VERIFIED, OrionEvidenceLedger.Outcome.EXECUTED_UNVERIFIED,
            OrionEvidenceLedger.Outcome.FAILED), rows.map { it.outcome })
        assertTrue(OrionEvidenceLedger.hasVerifiedEvidence(context, task))
    }

    @Test fun recordsRecoveryWithoutTreatingItAsVerification() {
        val task = "ledger-recovery-${System.nanoTime()}"
        assertTrue(OrionEvidenceLedger.recordRecovery(context, task, "sms", "stop-and-report", "user declined"))
        assertFalse(OrionEvidenceLedger.hasVerifiedEvidence(context, task))
        assertEquals(OrionEvidenceLedger.Outcome.RECOVERY, OrionEvidenceLedger.events(context, task).single().outcome)
    }

    @Test fun doesNotPersistRawArgumentsOrPrivateResults() {
        val task = "ledger-privacy-${System.nanoTime()}"
        val args = JSONObject().put("text", "private message").put("url", "https://example.com/private")
        assertTrue(OrionEvidenceLedger.recordDispatch(context, task, "device", "type", args, 1L, "typed private text"))
        val row = OrionEvidenceLedger.events(context, task).single()
        assertFalse(row.summary.contains("private message"))
        assertFalse(row.summary.contains("private text"))
        assertFalse(row.argsFingerprint.contains("private"))
        val file = File(context.filesDir, "orion_evidence_ledger.json")
        assertTrue(file.exists())
        val contents = file.readText()
        assertFalse(contents.contains("private message"))
        assertFalse(contents.contains("private text"))
    }
}
