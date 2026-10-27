package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionTaskOutcomeTest {
    private fun proof(tool: String, success: Boolean, verified: Boolean = false) =
        AgentHooks.Proof(tool, success, verified, if (success) "ok" else "error")

    @Test fun ordinarySuccessfulTaskIsReportedComplete() {
        val report = OrionTaskOutcome.reconcile(true, listOf(proof("open_app", true)), 0)
        assertEquals(OrionTaskOutcome.Status.COMPLETE, report.status)
        assertEquals(1, report.successfulSteps)
    }

    @Test fun recoveredFailureIsNotHiddenFromFinalReport() {
        val report = OrionTaskOutcome.reconcile(true, listOf(proof("open_app", false), proof("lookup_app", true)), 1)
        assertEquals(OrionTaskOutcome.Status.COMPLETE_WITH_RECOVERED_FAILURES, report.status)
        assertTrue(report.message.contains("failed before recovery"))
    }

    @Test fun noSuccessfulResultCannotBeReportedAsComplete() {
        val report = OrionTaskOutcome.reconcile(false, listOf(proof("send", false)), 1)
        assertEquals(OrionTaskOutcome.Status.FAILED, report.status)
        assertTrue(report.message.contains("not confirmed"))
    }

    @Test fun incompleteEvidenceIsReportedAsUnverified() {
        val report = OrionTaskOutcome.reconcile(false, listOf(proof("open_app", true)), 0)
        assertEquals(OrionTaskOutcome.Status.BLOCKED_OR_UNVERIFIED, report.status)
        assertTrue(report.message.contains("Do not claim success"))
    }

    @Test fun verifiedCountIsExplicit() {
        val report = OrionTaskOutcome.reconcile(true, listOf(
            proof("open_app", true),
            proof("send", true, verified = true)
        ), 0)
        assertEquals(1, report.verifiedSteps)
    }
}
