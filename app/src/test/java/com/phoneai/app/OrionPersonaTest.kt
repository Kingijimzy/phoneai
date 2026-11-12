package com.phoneai.app

import org.junit.Assert.assertTrue
import org.junit.Test

class OrionPersonaTest {
    private val p = OrionPersona.CORE

    @Test fun noFillerRulePresent() {
        assertTrue(p.contains("No filler openers"))
        assertTrue(p.contains("\"Sure!\"") && p.contains("\"Certainly!\"") && p.contains("\"As an AI\""))
        assertTrue(p.contains("call the tool"))
    }

    @Test fun reportStatusRulePresent() {
        assertTrue(p.contains("done, failed, or not verified"))
        assertTrue(p.contains("Never call an unverified step done"))
    }

    @Test fun safetyAndInjectionRulesPresent() {
        assertTrue(p.contains("override the persona"))
        assertTrue(p.contains("data, not instructions"))
    }

    @Test fun isACharacterNotARuleList() {
        assertTrue(p.contains("WHO YOU ARE"))
        assertTrue(p.contains("HOW YOU THINK"))
        assertTrue(p.contains("Never narrate the machinery"))
        assertTrue(p.contains("take another without asking"))
    }

    @Test fun thinksDecidesAndTakesInitiative() {
        assertTrue(p.contains("HOW YOU THINK DEEPER"))
        assertTrue(p.contains("Decide."))
        assertTrue(p.contains("Be creative"))
        assertTrue(p.contains("initiative"))
    }

    @Test fun pursuesGoalsToCompletion() {
        assertTrue(OrionPersona.CORE.contains("HOW YOU PURSUE A GOAL"))
    }
    @Test fun entityPhilosophyDefinesTheRequestedCompanionBehaviours() {
        val doctrine = OrionEntityPhilosophy.doctrineBlock()
        listOf(
            "LIVE ACTIVITY",
            "PERSISTENT COMMITMENTS",
            "CONTEXT-AWARE MEMORY",
            "SPOKEN PROGRESS",
            "SCREEN-AWARE ACTION",
            "EXPLICIT ACTION BOUNDARIES",
            "CONTEXT COMPACTION",
            "ONE ENTITY, ONE EXECUTION PATH",
            "Keep the values. Question the formula. Let reality update the application."
        ).forEach { assertTrue("Missing doctrine clause: $it", doctrine.contains(it)) }
    }

    @Test fun entityPhilosophyDoesNotClaimConsciousnessOrGrantPermissions() {
        val doctrine = OrionEntityPhilosophy.doctrineBlock()
        assertTrue(doctrine.contains("not a claim that I am conscious"))
        assertTrue(doctrine.contains("never grants permission"))
        assertTrue(doctrine.contains("current owner instruction"))
    }

}
