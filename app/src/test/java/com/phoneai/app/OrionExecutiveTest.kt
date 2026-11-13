package com.phoneai.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrionExecutiveTest {
    private val now = 10_000_000_000L
    private fun r(tool: String, ok: Boolean, reason: String = "", at: Long = now - 1000, key: String = tool) =
        OrionExperience.Record(tool, key, ok, reason, at)

    @Test fun signatureSeparatesDifferentTargets() {
        val a = OrionExperience.signature("open_app", JSONObject().put("package", "com.google.android.youtube"))
        val b = OrionExperience.signature("open_app", JSONObject().put("package", "com.whatsapp"))
        assertNotEquals(a, b)
        assertEquals(a, OrionExperience.signature("open_app", JSONObject().put("package", "COM.google.android.youtube")))
    }

    @Test fun failureStaysUnresolvedUntilTheSameActionSucceeds() {
        val list = listOf(r("gmail_list", false, "401 auth", at = 100), r("gmail_list", true, at = 200))
        assertTrue(OrionExperience.unresolvedFailures(list, 300).isEmpty())
        val still = listOf(r("gmail_list", false, "401 auth", at = 100), r("other", true, at = 200))
        assertEquals(1, OrionExperience.unresolvedFailures(still, 300).size)
    }

    @Test fun oldFailuresExpire() {
        assertTrue(OrionExperience.unresolvedFailures(listOf(r("x_tool", false, "bad", at = 1)), now).isEmpty())
    }

    @Test fun relevantFailuresAreRetrievedByRequestWords() {
        val list = listOf(r("youtube_open", false, "opened browser instead of app"), r("battery_info", false, "timeout"))
        val hits = OrionExperience.relevant(list, "open youtube for me", now)
        assertEquals(listOf("youtube_open"), hits.map { it.tool })
        assertTrue(OrionExperience.relevant(list, "zzzz qqqq", now).isEmpty())
    }

    @Test fun priorFailureMatchesOnSignature() {
        val list = listOf(r("shell", false, "permission denied", key = "shell|command=ls /root"))
        assertNotNull(OrionExperience.priorFailure(list, "shell|command=ls /root", now))
        assertNull(OrionExperience.priorFailure(list, "shell|command=ls /tmp", now))
    }

    @Test fun failureDirectiveOrdersDiagnoseRerouteVerifyAndNeverApology() {
        val out = OrionExecutive.directive("error: timeout", true, "PRIOR EXPERIENCE: failed before.")
        assertTrue(out.startsWith("error: timeout"))
        assertTrue(out.contains("do not apologise"))
        assertTrue(out.contains("Diagnose"))
        assertTrue(out.contains("Reroute"))
        assertTrue(out.contains("Verify"))
        assertTrue(out.contains("PRIOR EXPERIENCE"))
        assertEquals("ok result", OrionExecutive.directive("ok result", false, ""))
    }

    @Test fun pressureGrowsWithTimeAndCaps() {
        val s = OrionDrives.fresh(0)
        val later = OrionDrives.advance(s, 3 * 3_600_000L)
        assertEquals(0.3 + 0.10 * 3, later.pressure.getValue(OrionDrives.Drive.ORDER), 1e-9)
        val capped = OrionDrives.advance(s, 1000L * 3_600_000L)
        assertTrue(capped.pressure.values.all { it <= 1.0 })
    }

    @Test fun actingRelievesAndFailureRaisesPressure() {
        var s = OrionDrives.fresh(0)
        s = OrionDrives.adjust(s, OrionDrives.Drive.ORDER, 0.5)
        assertEquals(OrionDrives.Drive.ORDER, OrionDrives.dominant(s))
        s = OrionDrives.adjust(s, OrionDrives.Drive.ORDER, -0.9)
        assertEquals(0.0, s.pressure.getValue(OrionDrives.Drive.ORDER), 1e-9)
        assertNotEquals(OrionDrives.Drive.ORDER, OrionDrives.dominant(s))
    }
}
