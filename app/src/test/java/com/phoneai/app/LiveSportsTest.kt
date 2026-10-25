package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSportsTest {
    private val payload = """{"events":[
      {"status":{"type":{"state":"in","shortDetail":"67'"}},"competitions":[{"competitors":[
        {"homeAway":"home","score":"2","team":{"displayName":"Manchester United"}},
        {"homeAway":"away","score":"1","team":{"displayName":"AFC Bournemouth"}}]}]},
      {"status":{"type":{"state":"pre","shortDetail":"3:00 PM"}},"competitions":[{"competitors":[
        {"homeAway":"home","score":"0","team":{"displayName":"Chelsea"}},
        {"homeAway":"away","score":"0","team":{"displayName":"Arsenal"}}]}]}]}"""

    @Test fun detectsScoreQuestionsAndExtractsTeam() {
        assertTrue(LiveSports.isScoreRequest("What the score line on man united today"))
        assertFalse(LiveSports.isScoreRequest("what is my credit score"))
        assertEquals("manchester united", LiveSports.teamQuery("What the score line on man united today"))
    }

    @Test fun formatsLiveGameForTeam() {
        val lines = LiveSports.format(payload, "manchester united", "Premier League")
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("Manchester United 2 - 1 AFC Bournemouth") && lines[0].contains("LIVE 67'"))
    }

    @Test fun upcomingGameShowsKickoff() {
        val lines = LiveSports.format(payload, "arsenal", "Premier League")
        assertTrue(lines[0].contains("kicks off 3:00 PM"))
    }
}
