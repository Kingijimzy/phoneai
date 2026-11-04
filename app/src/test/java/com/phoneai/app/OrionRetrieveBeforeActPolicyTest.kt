package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionRetrieveBeforeActPolicyTest {
    @Test fun writesAndConsequentialActionsRequireRetrievalButReadsDoNot() {
        assertTrue(OrionRetrieveBeforeActPolicy.requiresRetrieval("telegram_send", ToolAccess.WRITE, true))
        assertTrue(OrionRetrieveBeforeActPolicy.requiresRetrieval("remember", ToolAccess.WRITE, false))
        assertTrue(OrionRetrieveBeforeActPolicy.requiresRetrieval("browser_click", ToolAccess.READ, true))
        assertFalse(OrionRetrieveBeforeActPolicy.requiresRetrieval("read_screen", ToolAccess.READ, false))
        assertFalse(OrionRetrieveBeforeActPolicy.requiresRetrieval("recall", ToolAccess.READ, false))
    }

    @Test fun successfulEmptyRetrievalIsAValidReceipt() {
        val receipt = OrionRetrieveBeforeActPolicy.receipt("Update my saved preference", "")
        assertNotNull(receipt)
        assertEquals(0, receipt!!.retrievedChars)
        assertNull(OrionRetrieveBeforeActPolicy.blockReason("Update my saved preference", receipt))
    }

    @Test fun missingMismatchedAndStaleReceiptsFailClosed() {
        assertTrue(OrionRetrieveBeforeActPolicy.blockReason("send this message", null).orEmpty().isNotBlank())
        val receipt = OrionRetrieveBeforeActPolicy.receipt("send this message", "prior decision", 1_000L)!!
        assertTrue(OrionRetrieveBeforeActPolicy.blockReason("delete this record", receipt, 1_001L).orEmpty().contains("different objective"))
        assertTrue(OrionRetrieveBeforeActPolicy.blockReason("send this message", receipt, 62_000L).orEmpty().contains("stale"))
    }

    @Test fun objectiveMatchingNormalizesWhitespaceAndCase() {
        val receipt = OrionRetrieveBeforeActPolicy.receipt("Send   this message", "context", 10L)!!
        assertNull(OrionRetrieveBeforeActPolicy.blockReason(" send this MESSAGE ", receipt, 11L))
    }

    @Test fun blankObjectiveCannotAuthorizeAWrite() {
        assertNull(OrionRetrieveBeforeActPolicy.receipt("   ", "context"))
        assertTrue(OrionRetrieveBeforeActPolicy.blockReason(" ", null).orEmpty().contains("objective"))
    }

    @Test fun finalDispatchGateNeverEntersExecutorWithoutValidReceipt() {
        var executions = 0
        val blocked = OrionRetrieveBeforeActPolicy.dispatchIfValid<String>(
            "send approved update", null, now = 10_000L
        ) { executions++; "executed" }
        assertTrue(blocked is OrionRetrieveBeforeActPolicy.DispatchGate.Blocked)
        assertEquals(0, executions)

        val receipt = OrionRetrieveBeforeActPolicy.receipt("send approved update", "", now = 10_000L)
        val stale = OrionRetrieveBeforeActPolicy.dispatchIfValid<String>(
            "send approved update", receipt, now = 70_001L
        ) { executions++; "executed" }
        assertTrue(stale is OrionRetrieveBeforeActPolicy.DispatchGate.Blocked)
        assertEquals(0, executions)

        val allowed = OrionRetrieveBeforeActPolicy.dispatchIfValid<String>(
            "send approved update", receipt, now = 10_001L
        ) { executions++; "executed" }
        assertEquals(OrionRetrieveBeforeActPolicy.DispatchGate.Allowed("executed"), allowed)
        assertEquals(1, executions)
    }

}
