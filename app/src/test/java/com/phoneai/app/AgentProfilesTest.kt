package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProfilesTest {
    @Test fun agentIdsAreUniqueAndOnlyTheCoachTakesImages() {
        val ids = AgentProfiles.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(AgentProfiles.find("coach")!!.acceptsImages)
        assertTrue(AgentProfiles.all.filter { it.id != "coach" }.none { it.acceptsImages })
    }

    @Test fun resolveAcceptsIdsAndFriendlyNames() {
        assertEquals("coach", AgentProfiles.resolve("Trading Coach")?.id)
        assertEquals("coder", AgentProfiles.resolve("coder")?.id)
        assertEquals("research", AgentProfiles.resolve("research analyst")?.id)
        assertNull(AgentProfiles.resolve("nobody"))
        assertNull(AgentProfiles.resolve(""))
    }

    @Test fun lessonsKeepRulesNotQuestionsOrSecrets() {
        val kept = AgentLessons.extract("What is BTC doing? I never trade during news events. My stop loss is always below the swing low. The password is hunter2 and my pin is 1234.")
        assertEquals(2, kept.size)
        assertTrue(kept.any { it.contains("never trade during news") })
        assertTrue(kept.any { it.contains("stop loss") })
        assertTrue(AgentLessons.extract("hello there how are you").isEmpty())
    }

    @Test fun rosterListsEveryAgent() {
        val roster = AgentProfiles.rosterBlock()
        AgentProfiles.all.forEach { assertTrue(roster.contains(it.id)) }
        assertNotNull(roster)
    }
}
