package com.phoneai.app

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class ModelRouterBackupTest {
    @Test fun shortSmallTalkIsFast() = assertEquals(ModelRouter.Tier.FAST, ModelRouter.tierFor("thanks, got it", false))
    @Test fun analysisIsStandard() = assertEquals(ModelRouter.Tier.CHAT, ModelRouter.tierFor("any setup on BTC?", false))
    @Test fun weeklyReviewIsDeep() = assertEquals(ModelRouter.Tier.DEEP, ModelRouter.tierFor("Review my week please", false))
    @Test fun imagesStayStandard() = assertEquals(ModelRouter.Tier.CHAT, ModelRouter.tierFor("hi", true))

    @Test fun onlyNvidiaTextIsRouted() {
        assertEquals(ModelRouter.FAST_MODEL, ModelRouter.modelFor(ModelRouter.Tier.FAST, "nvidia", false, true))
        assertEquals(ModelRouter.DEEP_MODEL, ModelRouter.modelFor(ModelRouter.Tier.DEEP, "nvidia", false, true))
        assertNull(ModelRouter.modelFor(ModelRouter.Tier.CHAT, "nvidia", false, true))
        assertNull(ModelRouter.modelFor(ModelRouter.Tier.FAST, "openai", false, true))
        assertNull(ModelRouter.modelFor(ModelRouter.Tier.FAST, "nvidia", true, true))
        assertNull(ModelRouter.modelFor(ModelRouter.Tier.DEEP, "nvidia", false, false))
    }

    @Test fun backupRoundTripsAndDropsSecretsAndSafetyState() {
        val prefs = mapOf(
            "trading_desk" to mapOf<String, Any?>("strategies" to "[1]", "kill" to true, "loss_streak" to 4, "paper" to "{}", "api_key" to "abc", "n" to 3L, "f" to 1.5f, "b" to true),
            "other_file" to mapOf<String, Any?>("x" to "y")
        )
        val j = JSONArray().put(org.json.JSONObject().put("fingerprint", "f1"))
        val r = DeskBackup.parse(DeskBackup.build(prefs, j, 1L))
        assertEquals(setOf("trading_desk"), r.prefs.keys)
        val m = r.prefs["trading_desk"]!!
        assertEquals("[1]", m["strategies"]); assertEquals(3L, m["n"]); assertEquals(1.5f, m["f"]); assertEquals(true, m["b"])
        assertFalse(m.containsKey("kill")); assertFalse(m.containsKey("loss_streak")); assertFalse(m.containsKey("api_key"))
        assertEquals(1, r.journal.length())
    }

    @Test fun memoryNotesRoundTrip() {
        val r = DeskBackup.parse(DeskBackup.build(emptyMap(), JSONArray(), 1L, listOf("[rule] never risk more than 1%", "my entry is on a retest")))
        assertEquals(listOf("[rule] never risk more than 1%", "my entry is on a retest"), r.memory)
        assertTrue(DeskBackup.parse("""{"app":"orion-desk","v":1}""").memory.isEmpty())
    }

    @Test fun tamperedBackupCannotRestoreSafetyKeys() {
        val text = """{"app":"orion-desk","v":1,"prefs":{"trading_desk":{"kill":{"t":"b","v":false},"deriv_token":{"t":"s","v":"x"},"paper":{"t":"s","v":"{}"}}},"journal":[]}"""
        val m = DeskBackup.parse(text).prefs["trading_desk"]!!
        assertEquals(setOf("paper"), m.keys)
    }

    @Test fun rejectsForeignAndNewerFiles() {
        assertFailsWith("not valid JSON") { DeskBackup.parse("hello") }
        assertFailsWith("not an Orion") { DeskBackup.parse("""{"app":"x","v":1}""") }
        assertFailsWith("newer version") { DeskBackup.parse("""{"app":"orion-desk","v":99}""") }
    }

    private fun assertFailsWith(part: String, f: () -> Unit) {
        try { f(); fail("expected failure") } catch (e: IllegalArgumentException) { assertTrue(e.message!!, e.message!!.contains(part)) }
    }
}
