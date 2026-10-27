package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class MorningBriefTest {
    @Test fun greetingFollowsTheHourAndName() {
        assertEquals("Good morning, Kingi", BriefComposer.greeting(8, "Kingi"))
        assertEquals("Good afternoon", BriefComposer.greeting(14, ""))
        assertEquals("Good evening, K", BriefComposer.greeting(19, "K"))
        assertEquals("Hello", BriefComposer.greeting(2, ""))
    }

    @Test fun composeIncludesOnlyWhatWasFetched() {
        val full = BriefComposer.compose(BriefParts("Kingi", "Lagos 29C, clear", "Man United 2-1 Arsenal (FT)", listOf("BTC 68,000", "ETH 3,400"), listOf("Pay rent", "Call Tunde"), 1, 4), 8)
        assertTrue(full.contains("Weather: Lagos") && full.contains("Your team: Man United") && full.contains("BTC 68,000 · ETH 3,400"))
        assertTrue(full.contains("Tasks: 2 open, 1 overdue - Pay rent; Call Tunde"))
        assertTrue(full.contains("4 lesson"))
        val bare = BriefComposer.compose(BriefParts(), 9)
        assertEquals(2, bare.lines().size)
        assertTrue(bare.contains("Nothing new"))
        assertFalse(BriefComposer.compose(BriefParts(weather = ""), 9).contains("Weather"))
    }

    @Test fun oneLineKeepsFirstNonBlankLineCapped() {
        assertEquals("Lagos 29C", BriefComposer.oneLine("\n  Lagos 29C  \nmore"))
        assertEquals(10, BriefComposer.oneLine("x".repeat(500), 10).length)
        assertEquals("", BriefComposer.oneLine(""))
    }

    @Test fun briefToolIsRegisteredAndPacked() {
        assertEquals(ToolAccess.READ, ToolRegistry.access("morning_brief"))
        assertEquals("orion_core", AgentPolicy.packForTool("morning_brief"))
    }
}
