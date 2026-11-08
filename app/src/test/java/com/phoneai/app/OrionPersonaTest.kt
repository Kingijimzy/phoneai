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
}
