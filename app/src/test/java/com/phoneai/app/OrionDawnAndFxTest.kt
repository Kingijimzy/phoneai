package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test

class OrionDawnAndFxTest {
    @Test fun anticipationNeedsRepeatedDaysAtTheSameHour() {
        val m = OrionIntentCompiler.Model()
        m.observe("bybit price", 33, 1); m.observe("bybit price", 33, 2)
        assertTrue(m.predict(33, 0, 3).isEmpty())
        m.observe("bybit price", 33, 3)
        val p = m.predict(33, 0, 3)
        assertEquals("bybit price", p.single().gist); assertEquals(3, p.single().days)
    }

    @Test fun lookAheadFindsTheNextHourAndWrapsTheWeek() {
        val m = OrionIntentCompiler.Model()
        (1..4).forEach { m.observe("morning brief", 1, it) }
        val p = m.predict(167, 2, 3)
        assertEquals(2, p.single().inHours)
    }

    @Test fun modelSurvivesJsonRoundTrip() {
        val m = OrionIntentCompiler.Model()
        (1..3).forEach { m.observe("check mail", 10, it) }
        assertEquals(1, OrionIntentCompiler.Model.fromJson(m.toJson()).predict(10, 0, 3).size)
    }

    @Test fun protocolNamesTheLoop() {
        assertTrue(OrionMase.PROTOCOL.contains("KNOW, ROUTE, ACT, REFLECT"))
    }

    @Test fun derivCandlesParseAndRequestIsWellFormed() {
        val c = FxFeeds.parseDeriv("""{"candles":[{"epoch":1700000000,"open":1.1,"high":1.2,"low":1.0,"close":1.15},{"epoch":1700000900,"open":1.15,"high":1.3,"low":1.1,"close":1.2}]}""")
        assertEquals(2, c.size); assertEquals(1_700_000_000_000L, c[0].t); assertEquals(1.2, c[1].c, 1e-9)
        assertEquals(900, FxFeeds.granularity("15m")); assertEquals(86_400, FxFeeds.granularity("1d"))
        assertTrue(FxFeeds.request("EURUSD", "1h")!!.contains("frxEURUSD"))
        assertEquals(null, FxFeeds.request("BTCUSD", "1h"))
    }

    @Test fun backupFeedIsUsedOnlyWhenPrimaryIsEmpty() {
        val one = listOf(Candle(1, 1.0, 1.0, 1.0, 1.0, 0.0))
        assertEquals(1, FxFeeds.candles("EURUSD", "1h", { _, _ -> emptyList() }, { _, _ -> one }).size)
        val got = FxFeeds.candles("EURUSD", "1h", { _, _ -> one }, { _, _ -> error("must not be called") })
        assertNotNull(got); assertEquals(1, got.size)
    }
}
