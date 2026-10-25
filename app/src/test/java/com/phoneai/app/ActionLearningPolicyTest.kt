package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionLearningPolicyTest {
    @Test fun classifiesRateLimitsWithoutRetainingRawError() {
        assertEquals("rate_limited", ActionLearningPolicy.classifyFailure("error: HTTP 429 quota exceeded for private-model-id"))
        assertEquals("rate limit/quota; wait or use the explicitly configured backup", ActionLearningPolicy.label("rate_limited"))
    }

    @Test fun classifiesRateLimitedTextEvenWithoutAnHttpCode() {
        assertEquals("rate_limited", ActionLearningPolicy.classifyFailure("error: provider rate limited this request"))
    }

    @Test fun classifiesPermissionAndTimeoutFailures() {
        assertEquals("permission_or_accessibility", ActionLearningPolicy.classifyFailure("error: accessibility service permission required"))
        assertEquals("network_or_timeout", ActionLearningPolicy.classifyFailure("error: connection timed out"))
    }

    @Test fun laterSuccessSupersedesAnOlderFailureForSameTool() {
        val records = listOf(
            ActionLearningRecord("click_text", "error returned", "target_not_found"),
            ActionLearningRecord("open_app", "error returned", "permission_or_accessibility"),
            ActionLearningRecord("click_text", "result returned", "")
        )
        val failures = ActionLearningPolicy.recentFailures(records)
        assertEquals(1, failures.size)
        assertEquals("open_app", failures.single().tool)
    }

    @Test fun neverClassifiesSuccessfulResultsAsFailures() {
        assertEquals("", ActionLearningPolicy.classifyFailure("Opened app successfully"))
    }

    @Test fun limitsFailureHintsAndKeepsOnlyLatestToolOutcome() {
        val records = (1..12).map { ActionLearningRecord("tool_$it", "error returned", "other_failure") }
        assertEquals(4, ActionLearningPolicy.recentFailures(records, 4).size)
        assertTrue(ActionLearningPolicy.recentFailures(records, 99).size <= 8)
    }
}
