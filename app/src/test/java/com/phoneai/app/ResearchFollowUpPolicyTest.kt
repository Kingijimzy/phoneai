package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class ResearchFollowUpPolicyTest {
    @Test fun recognizesNaturalResearchContinuations() {
        listOf("go and do your research about it", "do your research", "research it", "look it up", "verify it").forEach {
            assertTrue("Expected research follow-up: $it", ResearchFollowUpPolicy.isResearchFollowUp(it))
        }
    }

    @Test fun resolvesFollowUpToPreviousUserRequest() {
        val previous = "what was the score for Liverpool and Chelsea yesterday"
        assertEquals(previous, ResearchFollowUpPolicy.resolveTarget("go and do your research about it", previous))
        assertTrue(ResearchFollowUpPolicy.commandFor("research it", previous).contains(previous))
    }

    @Test fun doesNotRewriteUnrelatedOrContextlessMessages() {
        assertNull(ResearchFollowUpPolicy.resolveTarget("hello there", "what was the score yesterday"))
        assertNull(ResearchFollowUpPolicy.resolveTarget("research it", null))
        assertEquals("check this website", ResearchFollowUpPolicy.commandFor("check this website", "what was the score yesterday"))
    }
}
