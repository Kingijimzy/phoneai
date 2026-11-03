package com.phoneai.app

import com.phoneai.app.CompanionMood.Mood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionMoodTest {
    private fun inp(open: Double = 0.0, real: Double = 0.0, eq: Double = 1000.0, streak: Int = 0, news: Int? = null, kill: Boolean = false, practice: Boolean = false, nostop: Int = 0, data: Boolean = true) =
        CompanionMood.Input(open, real, eq, streak, news, kill, practice, nostop, data)

    @Test fun moodFollowsPnlThresholds() {
        assertEquals(Mood.CALM, CompanionMood.from(inp()).mood)
        assertEquals(Mood.HAPPY, CompanionMood.from(inp(real = 4.0)).mood)
        assertEquals(Mood.EXCITED, CompanionMood.from(inp(open = 10.0, real = 8.0)).mood)
        assertEquals(Mood.WORRIED, CompanionMood.from(inp(open = -20.0)).mood)
        assertTrue(CompanionMood.from(inp(open = 10.0, real = 8.0)).line.contains("+18.00"))
    }

    @Test fun safetyConditionsBeatPnl() {
        assertEquals(Mood.ALERT, CompanionMood.from(inp(real = 50.0, nostop = 2)).mood)
        assertEquals(Mood.ALERT, CompanionMood.from(inp(real = 50.0, news = 20)).mood)
        assertEquals(Mood.CALM, CompanionMood.from(inp(news = 90)).mood)
        assertEquals(Mood.WORRIED, CompanionMood.from(inp(real = 50.0, streak = 3)).mood)
        assertEquals(Mood.WORRIED, CompanionMood.from(inp(real = 50.0, kill = true)).mood)
    }

    @Test fun noDataIsCalmAndExplains() {
        val s = CompanionMood.from(inp(data = false))
        assertEquals(Mood.CALM, s.mood); assertTrue(s.line.contains("Practice mode"))
        assertTrue(CompanionMood.from(inp(practice = true)).line.contains("Practice account"))
        assertTrue(CompanionMood.from(inp(nostop = 1)).line.contains("1 open position has no stop"))
    }

    @Test fun speechIsRateLimited() {
        val t = 10_000_000L
        assertFalse(CompanionMood.shouldSpeak(Mood.CALM, Mood.CALM, 0, t))
        assertTrue(CompanionMood.shouldSpeak(Mood.CALM, Mood.EXCITED, 0, t))
        assertFalse(CompanionMood.shouldSpeak(Mood.CALM, Mood.EXCITED, t - 60_000L, t))
        assertFalse(CompanionMood.shouldSpeak(Mood.HAPPY, Mood.HAPPY, 0, t))
        assertTrue(CompanionMood.shouldSpeak(Mood.ALERT, Mood.ALERT, t - 31 * 60_000L, t))
        assertFalse(CompanionMood.shouldSpeak(Mood.ALERT, Mood.ALERT, t - 10 * 60_000L, t))
    }

    @Test fun stripText() {
        assertEquals("Open +12.30 · Today +45.10 (+5.7%) · 3 positions", PnlStrip.text(12.3, 45.1, 1000.0, 3, false))
        assertTrue(PnlStrip.text(0.0, 0.0, 0.0, 1, true).startsWith("Practice · "))
        assertTrue(PnlStrip.text(0.0, 0.0, 0.0, 1, true).endsWith("1 position"))
    }
}
