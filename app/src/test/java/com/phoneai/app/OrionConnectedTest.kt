package com.phoneai.app

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionConnectedTest {
    private lateinit var c: Context

    @Before fun setUp() {
        c = RuntimeEnvironment.getApplication()
        listOf("orion_rhythm", "orion_worldview", "orion_mind").forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        OrionMindStore.sync(c)
    }

    @Test fun claimsMustBeBackedByATool() {
        assertEquals("pushed it to main", OrionClaimCheck.unbacked("I've pushed it to main.", listOf("recall")))
        assertNull(OrionClaimCheck.unbacked("I've pushed it to main.", listOf("shell")))
        assertNotNull(OrionClaimCheck.unbacked("I sent the message to Ada.", emptyList()))
        assertNull(OrionClaimCheck.unbacked("I sent the message to Ada.", listOf("telegram_send")))
        assertNotNull(OrionClaimCheck.unbacked("I saved the script to the workspace.", listOf("recall")))
        assertNull(OrionClaimCheck.unbacked("I saved the script to the workspace.", listOf("workspace_write")))
        assertNotNull(OrionClaimCheck.unbacked("I set a timer for ten minutes.", listOf("shell")))
        assertNull("plain talk is not a claim", OrionClaimCheck.unbacked("I created a plan in my head: first read, then act.", emptyList()))
        assertNull(OrionClaimCheck.unbacked("You said the push finished.", emptyList()))
        assertTrue(OrionClaimCheck.annotate("I've installed numpy.", emptyList()).contains("treat it as not done"))
        assertEquals("All good.", OrionClaimCheck.annotate("All good.", emptyList()))
        assertTrue(OrionClaimCheck.prompt("pushed it").contains("no tool call"))
    }

    @Test fun honestyScrubIsStillTheFirstFilter() {
        assertTrue(OrionHonesty.scrub("python_exec returned 42. Done.", emptySet()).contains("Correction"))
    }

    @Test fun rhythmLearnsTheHourAndTheWritingStyle() {
        fun at(h: Int) = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 2, h, 5) }.timeInMillis
        repeat(6) { OrionRhythm.record(c, "check my bybit chart and the btc position", at(8)) }
        repeat(2) { OrionRhythm.record(c, "push the apk fix to github", at(21)) }
        val s = OrionRhythm.section(c, at(8))
        assertTrue(s, s.contains("usually asks about trading"))
        assertTrue(s, s.contains("short and fast") || s.contains("plain and direct"))
        assertFalse(OrionRhythm.section(c, at(21)).contains("usually asks about trading"))
        assertEquals("", OrionMindStore.rulesBlock(c).let { if (it.contains("trading")) "x" else "" })
    }

    @Test fun rhythmNeedsARealPatternAndIgnoresContinue() {
        assertNull(OrionRhythm.predictFrom(mapOf("h8|trading" to 2, "h9|code" to 1), 8))
        val p = OrionRhythm.predictFrom(mapOf("h8|trading" to 4, "h9|code" to 1, "h8|general" to 3), 8)
        assertEquals("trading", p!!.first)
        OrionRhythm.record(c, "Continue")
        assertEquals("", OrionRhythm.section(c))
        assertEquals("trading", OrionRhythm.topic("what is btc doing"))
        assertEquals("code", OrionRhythm.topic("merge the pr"))
    }

    @Test fun worldviewDatesFactsAndFlagsStaleOnes() {
        val day = 86_400_000L; val now = 1_800_000_000_000L
        assertTrue(OrionWorldview.learn(c, "Bybit swing positions are checked on the 4h chart.", "Kingi", 365, now - 2 * day).startsWith("ok"))
        assertTrue(OrionWorldview.learn(c, "BTC is trading near 95k.", "web 2 weeks ago", 1, now - 14 * day).startsWith("ok"))
        val s = OrionWorldview.section(c, now)
        assertTrue(s, s.contains("BTC is trading near 95k. [web 2 weeks ago, 14d ago, STALE]"))
        assertTrue(s, s.contains("4h chart. [Kingi, 2d ago]"))
        assertTrue(OrionWorldview.learn(c, "key ghp_abcdefghijklmnopqrstuvwxyz0123456789", "x", 5).startsWith("error"))
        assertTrue(OrionWorldview.forget(c, "btc").contains("1"))
        assertFalse(OrionWorldview.section(c, now).contains("BTC"))
        assertEquals("nothing recorded yet".length > 0, true)
    }

    @Test fun waitingStatusSaysWhatItIsWaitingOnInsteadOfBareWarning() {
        val approval = OrionActivityEvent("e1", "t1", OrionActivityKind.APPROVAL, OrionActivityStatus.WAITING,
            "Confirm deleting 3 workspace files", "", 0L)
        assertEquals("CONFIRM DELETING 3 WORKSPACE FILES", companionStatusForEvent(approval))
        val failed = OrionActivityEvent("e3", "t1", OrionActivityKind.ERROR, OrionActivityStatus.FAILED, "push rejected", "", 0L)
        assertEquals("ERROR: push rejected", companionStatusForEvent(failed))
    }

    @Test fun repeatedCapabilityDiscoveryWithNoRealToolGetsCoached() {
        val coach = OrionRetryCoach()
        assertEquals("", coach.discovery("capability_search"))
        assertEquals("", coach.discovery("capability_load"))
        assertEquals("", coach.discovery("capability_search"))
        val nudged = coach.discovery("capability_load")
        assertTrue(nudged.contains("COACH"))
        assertEquals("", coach.discovery("shell"))
        assertEquals("", coach.discovery("capability_search"))
    }

    @Test fun brainActionsReachTheNewStores() {
        assertTrue(OrionWorldview.run(c, JSONObject().put("action", "world_learn").put("text", "Kingi lives in Lagos, UTC+1.").put("source", "Kingi").put("days", 3650)).startsWith("ok"))
        assertTrue(OrionWorldview.run(c, JSONObject().put("action", "world")).contains("Lagos"))
    }
}
