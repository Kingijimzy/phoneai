package com.phoneai.app

import com.phoneai.app.TradingRisk.Account
import com.phoneai.app.TradingRisk.Limits
import com.phoneai.app.TradingRisk.Order
import org.junit.Assert.*
import org.junit.Test

class TradingDeskCoreTest {
    private val now = 1_000_000_000L
    private val acct = Account(equity = 10_000.0, dayStartEquity = 10_000.0, openPositions = 0, tradesToday = 0)
    private val limits = Limits(maxOrderUsd = 2_000.0, maxRiskPct = 1.0)
    private fun long(usd: Double, entry: Double = 100.0, stop: Double = 99.0, target: Double? = 102.0) = Order("BTCUSDT", "long", usd, entry, stop, target)

    @Test fun anOrderInsideEveryLimitIsAllowed() {
        val v = TradingRisk.evaluate(long(1_000.0), acct, limits, now)
        assertTrue(v.reasons.toString(), v.allowed)
        assertEquals(10.0, v.riskUsd, 1e-9); assertEquals(0.1, v.riskPct, 1e-9); assertEquals(2.0, v.rewardToRisk!!, 1e-9)
    }

    @Test fun missingOrWrongSideStopIsBlocked() {
        assertFalse(TradingRisk.evaluate(long(500.0, stop = 100.0), acct, limits, now).allowed)
        assertFalse(TradingRisk.evaluate(long(500.0, stop = 101.0), acct, limits, now).allowed)
        assertFalse(TradingRisk.evaluate(Order("EURUSD", "short", 500.0, 1.10, 1.09, 1.08), acct, limits, now).allowed)
        assertFalse(TradingRisk.evaluate(long(500.0, target = 99.0), acct, limits, now).allowed)
    }

    @Test fun sizeAndRiskLimitsBlockWithASuggestedSize() {
        val big = TradingRisk.evaluate(long(5_000.0), acct, limits, now)
        assertFalse(big.allowed)
        val risky = TradingRisk.evaluate(long(2_000.0, stop = 90.0, target = 130.0), acct, limits, now) // 10% move on 2000 = 200 = 2%
        assertFalse(risky.allowed)
        assertTrue(risky.reasons.any { it.contains("Risk") })
        assertEquals(1_000.0, risky.suggestedUsd!!, 1e-6) // 1% of 10000 over a 10% stop
    }

    @Test fun rewardToRiskBelowMinimumIsBlocked() {
        assertFalse(TradingRisk.evaluate(long(500.0, target = 100.5), acct, limits, now).allowed)
    }

    @Test fun dailyLossLimitStopsTradingForTheDay() {
        val down = acct.copy(equity = 9_600.0) // -4% vs 3% limit
        val v = TradingRisk.evaluate(long(500.0), down, limits, now)
        assertFalse(v.allowed); assertTrue(v.reasons.any { it.contains("daily limit") })
    }

    @Test fun positionAndTradeCountLimitsApply() {
        assertFalse(TradingRisk.evaluate(long(500.0), acct.copy(openPositions = 3), limits, now).allowed)
        assertFalse(TradingRisk.evaluate(long(500.0), acct.copy(tradesToday = 6), limits, now).allowed)
    }

    @Test fun lossStreakPausesThenResumes() {
        val streak = acct.copy(consecutiveLosses = 3, lastLossAtMillis = now - 10 * 60_000L)
        assertFalse(TradingRisk.evaluate(long(500.0), streak, limits, now).allowed)
        assertTrue(TradingRisk.evaluate(long(500.0), streak, limits, now + 61 * 60_000L).allowed)
    }

    @Test fun killSwitchAndNewsBlackoutAlwaysBlock() {
        assertFalse(TradingRisk.evaluate(long(500.0), acct, limits, now, killSwitch = true).allowed)
        assertFalse(TradingRisk.evaluate(long(500.0), acct, limits, now, newsBlackout = true).allowed)
    }

    @Test fun sizeForRiskMatchesTheStopDistance() {
        assertEquals(1_000.0, TradingRisk.sizeForRisk(10_000.0, 1.0, 100.0, 90.0), 1e-9)
        assertEquals(0.0, TradingRisk.sizeForRisk(10_000.0, 1.0, 100.0, 100.0), 0.0)
    }

    // ---- paper broker

    @Test fun paperLongHitsTargetAndRecordsRMultiple() {
        var s = PaperBroker.start(10_000.0)
        s = PaperBroker.open(s, "BTCUSDT", true, 1_000.0, 100.0, 99.0, 102.0, "breakout", 1L)
        s = PaperBroker.onCandle(s, "BTCUSDT", high = 102.5, low = 99.5, now = 2L)
        assertEquals(1, s.closed.size); assertEquals("target", s.closed[0].reason)
        assertEquals(2.0, s.closed[0].rMultiple, 1e-9); assertEquals(10_020.0, s.balance, 1e-6); assertTrue(s.open.isEmpty())
    }

    @Test fun stopWinsWhenOneCandleTouchesBoth() {
        var s = PaperBroker.open(PaperBroker.start(10_000.0), "ETHUSDT", true, 1_000.0, 100.0, 99.0, 102.0, "x", 1L)
        s = PaperBroker.onCandle(s, "ETHUSDT", 103.0, 98.0, 2L)
        assertEquals("stop", s.closed[0].reason); assertEquals(-1.0, s.closed[0].rMultiple, 1e-9)
    }

    @Test fun paperShortAndOtherSymbolsAreIndependent() {
        var s = PaperBroker.open(PaperBroker.start(1_000.0), "EURUSD", false, 500.0, 1.10, 1.11, 1.08, "pullback", 1L)
        s = PaperBroker.onCandle(s, "BTCUSDT", 9e9, 0.0, 2L)
        assertEquals(1, s.open.size)
        s = PaperBroker.onCandle(s, "EURUSD", 1.105, 1.079, 3L)
        assertEquals("target", s.closed[0].reason); assertTrue(s.balance > 1_000.0)
    }

    @Test fun manualCloseUsesThePriceGiven() {
        var s = PaperBroker.open(PaperBroker.start(1_000.0), "SOLUSDT", true, 200.0, 50.0, 49.0, null, "x", 1L)
        s = PaperBroker.closeAt(s, 1, 51.0, 2L)
        assertEquals("manual", s.closed[0].reason); assertEquals(1.0, s.closed[0].rMultiple, 1e-9)
        assertSame(s, PaperBroker.closeAt(s, 99, 1.0, 3L))
    }

    // ---- stats and the "do not learn from noise" rule

    @Test fun summaryMathIsCorrect() {
        val sm = TradingStats.summarize(listOf(2.0, -1.0, -1.0, 2.0, -1.0))
        assertEquals(5, sm.trades); assertEquals(0.4, sm.winRate, 1e-9); assertEquals(0.2, sm.avgR, 1e-9)
        assertEquals(4.0 / 3.0, sm.profitFactor!!, 1e-9); assertEquals(2.0, sm.maxDrawdownR, 1e-9); assertEquals(2, sm.longestLossStreak)
        assertEquals(0, TradingStats.summarize(emptyList()).trades)
    }

    @Test fun thinSamplesNeverProduceAVerdict() {
        assertEquals(TradingStats.Verdict.TOO_FEW_TRADES, TradingStats.verdict(TradingStats.summarize(List(19) { 3.0 })))
    }

    @Test fun verdictsFollowExpectancyOnceThereIsEnoughData() {
        assertEquals(TradingStats.Verdict.PROMISING, TradingStats.verdict(TradingStats.summarize(List(20) { if (it % 2 == 0) 2.0 else -1.0 })))
        assertEquals(TradingStats.Verdict.WEAK, TradingStats.verdict(TradingStats.summarize(List(20) { if (it % 4 == 0) 1.0 else -1.0 })))
        assertEquals(TradingStats.Verdict.KEEP_WATCHING, TradingStats.verdict(TradingStats.summarize(List(20) { if (it % 3 == 0) 1.5 else -0.7 })))
    }

    @Test fun bySetupSplitsResults() {
        val c = { id: Int, setup: String, r: Double -> PaperBroker.Closed(id, "X", true, setup, 1.0, 1.0, "t", 0.0, r, 0L) }
        val m = TradingStats.bySetup(listOf(c(1, "a", 1.0), c(2, "b", -1.0), c(3, "a", 2.0)))
        assertEquals(2, m["a"]!!.trades); assertEquals(1, m["b"]!!.trades)
    }

    // ---- sessions

    @Test fun sessionsAndOverlaps() {
        assertEquals(listOf("London", "New York"), MarketSessions.active(13))
        assertTrue(MarketSessions.describe(13).contains("overlap"))
        assertTrue(MarketSessions.active(23).contains("Sydney"))
        assertTrue(MarketSessions.inPreferredSession("EURUSD", 9))
        assertFalse(MarketSessions.inPreferredSession("EURUSD", 3))
    }
}
