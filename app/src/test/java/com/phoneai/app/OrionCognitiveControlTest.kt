package com.phoneai.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionCognitiveControlTest {
    private val world = JSONObject().put("network", "wifi")
    @Test fun screenSwitchBlockedWhenOwnerDidNotAsk() {
        val f = OrionForesight.assess("open_app", world, "fix the sandbox policy error")
        assertEquals(OrionForesight.Level.BLOCK, f.level)
        assertEquals(OrionForesight.Level.BLOCK, OrionForesight.assess("open_page", world, "").level)
    }
    @Test fun screenSwitchAllowedWhenAsked() {
        assertEquals(OrionForesight.Level.OK, OrionForesight.assess("open_app", world, "open WhatsApp").level)
        assertTrue(OrionForesight.ownerWantsScreenChange("take me to settings"))
        assertFalse(OrionForesight.ownerWantsScreenChange("analyse these two channels"))
    }
    @Test fun repeatedUnforeseenFailureBecomesALesson() {
        assertNull(OrionForesight.compare("zz_tool", OrionForesight.Level.OK, true, "error: boom"))
        assertNotNull(OrionForesight.compare("zz_tool", OrionForesight.Level.OK, true, "error: boom"))
        assertTrue(OrionForesight.calibration().contains("zz_tool"))
    }
    @Test fun foresightLedgerRoundTripsThroughStorage() {
        OrionForesight.compare("zz_probe_tool", OrionForesight.Level.OK, true, "boom")
        val saved = OrionForesight.serialize()
        assertTrue(saved.contains("zz_probe_tool"))
        val before = OrionForesight.stats()["zz_probe_tool"]!!.second
        OrionForesight.restore(saved)
        assertEquals(before * 2, OrionForesight.stats()["zz_probe_tool"]!!.second)
    }
    @Test fun upcomingEventsAloneCanOpenTheProactiveGate() {
        val s = OrionProactiveGate.Signals(5.0, 14, 30.0, 80, false, 0, 0, 0, 0, 0, 0, calendarSoon = 1)
        assertTrue(OrionProactiveGate.decide(s).open)
    }
}
