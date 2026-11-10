package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionSoulEnginePromptPolicyTest {
    @Test fun soulEngineHonorsPerToolAllowAskDenyAndActionSpecificApproval() {
        val prompt = OrionSoulEnginePromptPolicy.OWNER_PERMISSION_AND_CAPABILITY_CONTRACT
        assertTrue(prompt.contains("ALLOW may execute"))
        assertTrue(prompt.contains("ASK must use the owner approval surface"))
        assertTrue(prompt.contains("DENY must never be bypassed"))
        assertTrue(prompt.contains("Autopilot never overrides explicit ASK or DENY"))
        assertTrue(prompt.contains("Trading Desk writes and destructive deletion/removal"))
        assertFalse(prompt.contains("Only restart, shutdown"))
    }
}
