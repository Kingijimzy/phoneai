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

    @Test fun ordinaryResultsRemainEligibleForNormalVerification() {
        assertNull(ToolResultPolicy.failureReason("Opened Settings"))
        assertNull(ToolResultPolicy.failureReason("Found 3 matching items"))
        assertNull(ToolResultPolicy.failureReason("Message draft prepared; not sent"))
    }
}
