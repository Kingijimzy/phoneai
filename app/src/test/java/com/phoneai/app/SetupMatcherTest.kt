package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupMatcherTest {
    private fun setup(sym: String, type: String = "breakout", tf: String = "1h", score: Int = 60, entry: Double = 100.0, stop: Double = 99.0, target: Double = 102.0) =
        Setup(sym, tf, type, "long", entry, stop, target, score, "")

    private val st = StrategySpec("London breakouts", instruments = listOf("EURUSD"), timeframes = listOf("1h"), setupTypes = listOf("breakout"), minRewardToRisk = 1.5)

    @Test fun matchesOnlyWhatTheStrategyAllows() {
        val r = SetupMatcher.rank(listOf(setup("EURUSD"), setup("GBPUSD"), setup("EURUSD", tf = "15m"), setup("EURUSD", type = "pullback")), listOf(st), 9, { false })
        assertEquals(1, r.size)
        assertEquals("EURUSD", r[0].setup.symbol)
    }

    @Test fun lowRewardToRiskIsDropped() {
        val r = SetupMatcher.rank(listOf(setup("EURUSD", target = 100.5)), listOf(st), 9, { false })
        assertTrue(r.isEmpty())
    }

    @Test fun newsBlockedSetupsAreExcluded() {
        val r = SetupMatcher.rank(listOf(setup("EURUSD")), listOf(st), 9, { it == "EURUSD" })
        assertTrue(r.isEmpty())
    }

    @Test fun losingSetupTypeIsPenalisedAndWinningBoosted() {
        val base = SetupMatcher.rank(listOf(setup("EURUSD")), listOf(st), 9, { false })[0].score
        val bad = SetupMatcher.rank(listOf(setup("EURUSD")), listOf(st), 9, { false }, { 0.2 })[0].score
        val good = SetupMatcher.rank(listOf(setup("EURUSD")), listOf(st), 9, { false }, { 0.7 })[0].score
        assertTrue(bad < base)
        assertTrue(good > base)
    }

    @Test fun ranksBestFirst() {
        val any = StrategySpec("any")
        val r = SetupMatcher.rank(listOf(setup("A", score = 40), setup("B", score = 80)), listOf(any), 9, { false })
        assertEquals("B", r[0].setup.symbol)
    }
}
