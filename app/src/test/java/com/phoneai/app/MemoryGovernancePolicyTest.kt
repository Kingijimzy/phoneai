package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryGovernancePolicyTest {
    @Test fun generatedMemoriesRequireOwnerReviewBeforePromptUse() {
        assertFalse(MemoryGovernancePolicy.canUseInPrompt("pending"))
        assertFalse(MemoryGovernancePolicy.canUseInPrompt("rejected"))
        assertTrue(MemoryGovernancePolicy.canUseInPrompt("approved"))
    }

    @Test fun sensitiveMemoriesNeverEnterModelContextEvenAfterApproval() {
        assertEquals("sensitive", MemoryGovernancePolicy.sensitivity("my API key is abc123"))
        assertEquals("sensitive", MemoryGovernancePolicy.sensitivity("a normal note", "sensitive"))
        assertFalse(MemoryGovernancePolicy.canUseInPrompt("approved", "sensitive"))
        assertTrue(MemoryGovernancePolicy.canUseInPrompt("approved", "general"))
    }

    @Test fun confidenceIsBoundedAndNonFiniteValuesAreRejected() {
        assertEquals(1.0, MemoryGovernancePolicy.normalizeConfidence(2.0), 0.0)
        assertEquals(0.0, MemoryGovernancePolicy.normalizeConfidence(-1.0), 0.0)
        assertEquals(0.0, MemoryGovernancePolicy.normalizeConfidence(Double.NaN), 0.0)
        assertEquals(0.0, MemoryGovernancePolicy.normalizeConfidence(Double.POSITIVE_INFINITY), 0.0)
    }
}
