package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeskProposalsTest {
    private val resolve = { s: String -> s.uppercase().replace("/", "").takeIf { it in setOf("EURUSD", "GBPUSD", "BTCUSDT") } }

    @Test fun parsesStrategyLineAndHidesIt() {
        val r = DeskProposals.parse("Here is the idea.\nSTRATEGY: London breakouts | EURUSD, GBP/USD | 1h, 4h | Breakout | 2 | wait for the Asian range\nDone.", resolve)
        assertEquals(1, r.strategies.size)
        val s = r.strategies[0]
        assertEquals("London breakouts", s.name)
        assertEquals(listOf("EURUSD", "GBPUSD"), s.instruments)
        assertEquals(listOf("1h", "4h"), s.timeframes)
        assertEquals(listOf("breakout"), s.setupTypes)
        assertEquals(2.0, s.minRewardToRisk, 0.0)
        assertTrue(s.notes.contains("Asian"))
        assertTrue(!r.visible.contains("STRATEGY"))
        assertTrue(r.visible.contains("Here is the idea."))
    }

    @Test fun anyMeansNoFilterAndBadRrFallsBack() {
        val s = DeskProposals.parse("STRATEGY: Wide | any | any | any | x", resolve).strategies[0]
        assertTrue(s.instruments.isEmpty() && s.timeframes.isEmpty() && s.setupTypes.isEmpty())
        assertEquals(1.5, s.minRewardToRisk, 0.0)
    }

    @Test fun parsesPaperTradeAndRejectsUnknownSymbolOrMissingStop() {
        val r = DeskProposals.parse("PAPER: long EURUSD 500 stop=1.0700 target=1.0900 | pullback\nPAPER: long FOOBAR 100 stop=1\nPAPER: short EURUSD 100", resolve)
        assertEquals(1, r.papers.size)
        val p = r.papers[0]
        assertTrue(p.long); assertEquals("EURUSD", p.symbol); assertEquals(500.0, p.usd, 0.0); assertEquals(1.07, p.stop, 1e-9); assertEquals(1.09, p.target!!, 1e-9)
    }

    @Test fun nothingToParseKeepsReplyIntact() {
        val r = DeskProposals.parse("Just talking.", resolve)
        assertEquals("Just talking.", r.visible); assertTrue(r.strategies.isEmpty() && r.papers.isEmpty())
    }

    @Test fun derivPlanKeepsLossAtStopEqualToPlannedRisk() {
        val p = DerivSizing.plan(1000.0, 1.0800, 1.0750, 1.0900)!!
        val d = 0.0050 / 1.08
        assertEquals(1000.0 * d, p.riskUsd, 1e-6)
        assertTrue(p.stopLossUsd <= p.stake + 1e-9)
        assertTrue(p.multiplier <= (1.0 / d).toInt())
        assertNotNull(p.takeProfitUsd)
        assertTrue(p.takeProfitUsd!! > p.stopLossUsd)
    }

    @Test fun derivPlanRejectsStopsTooTightOrInvalid() {
        assertNull(DerivSizing.plan(1000.0, 1.0, 0.90, null)) // a 10% stop is wider than the smallest multiplier allows
        assertNull(DerivSizing.plan(0.0, 1.0, 0.99, null))
        assertNull(DerivSizing.plan(100.0, 1.0, 1.0, null))
    }
}
