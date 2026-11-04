package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class ToolResultPolicyTest {
    @Test fun explicitFailuresAreNotReportedAsSuccess() {
        listOf(
            "ERROR: accessibility permission missing",
            "Blocked: tool is outside task scope",
            "Timed out waiting for page",
            "error: no such app",
            "failed to connect",
            "not found: Settings",
            "unavailable: accessibility is off",
            "not configured: Gmail",
            "invalid coordinates",
            "rejected by the user",
            "refused",
            "could not open the browser",
            "cannot reach the network",
            "cancelled: the user declined",
            ""
        ).forEach { assertNotNull(it, ToolResultPolicy.failureReason(it)) }
    }

    @Test fun ordinaryResultsRemainEligibleForNormalVerification() {
        listOf(
            "Opened Settings",
            "Found 3 matching items",
            "Message draft prepared; not sent",
            "Search results for cat videos",
            "Deleted task #4.",
            "3 unread messages"
        ).forEach { assertNull(it, ToolResultPolicy.failureReason(it)) }
    }

    @Test fun anEmptyResultIsAFailure() {
        assertNotNull(ToolResultPolicy.failureReason("   "))
    }
}
