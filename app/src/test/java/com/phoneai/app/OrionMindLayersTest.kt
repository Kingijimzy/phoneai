package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionMindLayersTest {
    @Test fun tomAgreementRaisesConfidence() {
        val r = OrionFactStore.mergeTom("brief", 0.6, "inferred", 1, "brief", 0.7, false)!!
        assertEquals("brief", r.first); assertTrue(r.second > 0.6); assertEquals(2, r.third)
    }
    @Test fun tomWeakContradictionKeepsBelief() {
        assertNull(OrionFactStore.mergeTom("brief", 0.7, "inferred", 3, "detailed", 0.75, false))
        assertEquals("detailed", OrionFactStore.mergeTom("brief", 0.6, "inferred", 1, "detailed", 0.8, false)!!.first)
    }
    @Test fun tomOwnerValueIsNeverOverwrittenByInference() {
        assertNull(OrionFactStore.mergeTom("terse", 1.0, "owner", 1, "chatty", 0.9, false))
        assertEquals("chatty", OrionFactStore.mergeTom("terse", 1.0, "owner", 1, "chatty", 1.0, true)!!.first)
    }
    @Test fun tomLowConfidenceFirstObservationIgnored() {
        assertNull(OrionFactStore.mergeTom(null, 0.0, "", 0, "x", 0.3, false))
    }
    private fun sig(h: Double = 6.0, hour: Int = 14, idle: Double = 60.0, broken: Int = 0, due: Int = 0, added: Int = 0, today: Int = 0, bat: Int = 80) =
        OrionProactiveGate.Signals(h, hour, idle, bat, false, broken, due, due, added, 0, today)
    @Test fun gateClosedWithNothingToDo() = assertFalse(OrionProactiveGate.decide(sig()).open)
    @Test fun gateOpensForDueWork() = assertTrue(OrionProactiveGate.decide(sig(due = 1)).open)
    @Test fun gateOpensForBrokenToolsAndNewCapabilities() = assertTrue(OrionProactiveGate.decide(sig(broken = 2)).open)
    @Test fun gateRespectsBusyOwnerBudgetBatteryAndCooldown() {
        assertFalse(OrionProactiveGate.decide(sig(due = 3, idle = 1.0)).open)
        assertFalse(OrionProactiveGate.decide(sig(due = 3, today = 4)).open)
        assertFalse(OrionProactiveGate.decide(sig(due = 3, bat = 10)).open)
        assertFalse(OrionProactiveGate.decide(sig(due = 3, h = 0.5)).open)
    }
    @Test fun gateIsStricterAtNight() {
        assertTrue(OrionProactiveGate.decide(sig(due = 1, hour = 3)).score >= 1.0)
        assertFalse(OrionProactiveGate.decide(sig(broken = 2, hour = 3)).open)
    }
    @Test fun diffNeedsBaselineThenFindsChanges() {
        assertEquals(emptyList<String>() to emptyList<String>(), OrionCapabilityAwareness.diff(null, setOf("a")))
        assertEquals(listOf("c") to listOf("a"), OrionCapabilityAwareness.diff(setOf("a", "b"), setOf("b", "c")))
    }
    @Test fun layersAssembleWithinBudgetAndKeepArbitration() {
        val out = OrionMemoryLayers.assemble(listOf(
            OrionMemoryLayers.Layer("L1", "x".repeat(5000), 1),
            OrionMemoryLayers.Layer("L2", "owner likes brief", 5)), 600)
        assertTrue(out.contains("owner likes brief")); assertTrue(out.contains("ARBITRATION"))
        assertTrue(out.indexOf("[L1]") < out.indexOf("[L2]"))
    }
    @Test fun emptyLayersGiveNothing() = assertEquals("", OrionMemoryLayers.assemble(emptyList()))
}
