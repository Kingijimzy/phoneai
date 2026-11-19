package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionAcknowledgeTest {
    @Test fun neverRepeatsTheSameOpenerTwiceInARow() {
        val seq = (1..20).map { OrionAcknowledge.nextLine() }
        seq.zipWithNext().forEach { (a, b) -> assertNotEquals(a, b) }
    }

    @Test fun fastAnswersNeverTriggerIt() {
        var spoken = 0
        val h = OrionAcknowledge.arm(delayMs = 150) { spoken++ }
        h.cancel()
        Thread.sleep(400)
        assertEquals(0, spoken)
    }

    @Test fun realSpeechSilencesIt() {
        var spoken = 0
        OrionAcknowledge.arm(delayMs = 150) { spoken++ }
        OrionAcknowledge.noteSpeech()
        Thread.sleep(400)
        assertEquals(0, spoken)
    }

    @Test fun silenceForLongerThanTheDelaySpeaksOnce() {
        var spoken = 0
        OrionAcknowledge.arm(delayMs = 100) { spoken++ }
        Thread.sleep(500)
        assertEquals(1, spoken)
    }

    @Test fun identityAndMissionSectionsAreCoreSoRelevanceNeverDefersThem() {
        listOf("ORION ENTITY PHILOSOPHY — v", "EXECUTION ENVELOPE (measured now)", "MUSCLE MEMORY — LEARNED ROUTE (act first)", "ORION SELF-MODEL — OBSERVED").forEach {
            assertTrue(it, ContextWindowBudget.priority(it + "\n" + "x ".repeat(2000)) >= ContextRelevance.CORE_PRIORITY)
        }
    }
}
