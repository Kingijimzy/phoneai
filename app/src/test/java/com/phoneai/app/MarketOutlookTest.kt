package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketOutlookTest {
    private fun candles(n: Int, start: Double, step: Double): List<Candle> =
        (0 until n).map { i -> val c = start + step * i; Candle(i * 3_600_000L, c - step / 2, c + 0.5, c - 0.5, c, 100.0) }

    @Test fun steadyUptrendIsBullishAndCappedConfidence() {
        val up = candles(220, 100.0, 1.0)
        val outlook = MarketOutlook.compute("TESTUSDT", up, up, up)!!
        assertEquals(1, outlook.bias)
        assertTrue(outlook.confidence <= 75)
        assertTrue(outlook.text.contains("not a guarantee"))
    }

    @Test fun steadyDowntrendIsBearish() {
        val down = candles(220, 500.0, -1.0)
        assertEquals(-1, MarketOutlook.compute("TESTUSDT", down, down, down)!!.bias)
    }

    @Test fun tooLittleDataGivesNoOutlook() {
        assertNull(MarketOutlook.compute("X", candles(20, 1.0, 0.1), candles(20, 1.0, 0.1), candles(20, 1.0, 0.1)))
    }

    @Test fun judgingOutlooksCountsOnlyRealMoves() {
        assertEquals(true, MarketOutlook.judge(1, 100.0, 103.0))
        assertEquals(false, MarketOutlook.judge(1, 100.0, 97.0))
        assertEquals(true, MarketOutlook.judge(-1, 100.0, 97.0))
        assertNull(MarketOutlook.judge(1, 100.0, 100.1))
        assertNull(MarketOutlook.judge(0, 100.0, 120.0))
    }

    @Test fun coachMemoryLinesAreParsedAndHiddenFromTheUser() {
        val reply = "Bias is bullish above 60k.\nMEMORY: BTC 4h reclaimed the 20 EMA\nMISTAKE: entered before candle close\nRULES: Only trade with 4h trend | Risk max 1% per trade\nMEMORY: my password is hunter2 and card number 4111 1111 1111 1111"
        val parsed = CoachMemory.parse(reply)
        assertEquals("Bias is bullish above 60k.", parsed.visible)
        assertEquals(listOf("chart", "mistake", "rule", "rule"), parsed.notes.map { it.first })
        assertFalse(parsed.notes.any { it.second.contains("4111") })
        assertNotNull(parsed.notes.firstOrNull { it.second.startsWith("Risk max") })
    }
}
