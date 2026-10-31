package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class ToolResultPolicyTest {
    @Test fun explicitFailuresAreNotReportedAsSuccess() {
        assertNotNull(ToolResultPolicy.failureReason("ERROR: accessibility permission missing"))
        assertNotNull(ToolResultPolicy.failureReason("Blocked: tool is outside task scope"))
        assertNotNull(ToolResultPolicy.failureReason("Timed out waiting for page"))
        assertNotNull(ToolResultPolicy.failureReason(""))
    }

    @Test fun failuresAreClassifiedWithSafeNonBypassingAdvice() {
        assertEquals(ToolResultPolicy.FailureKind.PERMISSION, ToolResultPolicy.diagnose("error: accessibility permission missing").kind)
        assertEquals(ToolResultPolicy.FailureKind.MISSING_DEPENDENCY, ToolResultPolicy.diagnose("error: command not found").kind)
        assertEquals(ToolResultPolicy.FailureKind.TIMEOUT, ToolResultPolicy.diagnose("Timed out waiting for page").kind)
        assertEquals(ToolResultPolicy.FailureKind.NETWORK_OR_PROVIDER, ToolResultPolicy.diagnose("HTTP 503 provider unavailable").kind)
        assertEquals(ToolResultPolicy.FailureKind.INVALID_OUTPUT, ToolResultPolicy.diagnose("error: invalid JSON output").kind)
        assertEquals(ToolResultPolicy.FailureKind.DENIED_OR_BLOCKED, ToolResultPolicy.diagnose("Blocked: tool is outside task scope").kind)
        assertTrue(ToolResultPolicy.diagnose("Blocked: action outside task scope").safeNextStep.contains("never bypass"))
    }

    @Test fun ordinaryResultsRemainEligibleForNormalVerification() {
        assertNull(ToolResultPolicy.failureReason("Opened Settings"))
        assertNull(ToolResultPolicy.failureReason("Found 3 matching items"))
        assertNull(ToolResultPolicy.failureReason("Message draft prepared; not sent"))
    }
}
