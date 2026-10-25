package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRankingTest {
    private fun mem(id: Int, cat: String, text: String, updated: Long, pinned: Boolean = false) =
        Memory(id, cat, text, updated, updated, pinned)

    @Test fun changedFactSupersedesNearlyIdenticalNote() {
        val old = mem(1, "preference", "User prefers dark theme in the app", 1)
        assertEquals(old, MemoryRanking.findSuperseded(listOf(old), "preference", "User prefers light theme in the app"))
    }

    @Test fun unrelatedOrPinnedOrOtherCategoryIsNotReplaced() {
        val pinned = mem(1, "preference", "User prefers dark theme in the app", 1, pinned = true)
        assertNull(MemoryRanking.findSuperseded(listOf(pinned), "preference", "User prefers light theme in the app"))
        val other = mem(2, "project", "User prefers dark theme in the app", 1)
        assertNull(MemoryRanking.findSuperseded(listOf(other), "preference", "User prefers light theme in the app"))
        val unrelated = mem(3, "preference", "Likes jollof rice on weekends", 1)
        assertNull(MemoryRanking.findSuperseded(listOf(unrelated), "preference", "User prefers light theme in the app"))
    }

    @Test fun selectKeepsPinnedAndRelevantBeforeRecent() {
        val list = listOf(
            mem(1, "fact", "Birthday is in March", 1, pinned = true),
            mem(2, "project", "Orion trading agent uses Bybit", 2),
            mem(3, "fact", "Likes football", 100),
            mem(4, "fact", "Drinks tea", 101)
        )
        val picked = MemoryRanking.select(list, "what is my bybit trading plan", limit = 3, relevantLimit = 2).map { it.id }
        assertEquals(listOf(1, 2, 4), picked)
    }

    @Test fun scoreIsZeroWithoutOverlap() {
        assertEquals(0, MemoryRanking.score(mem(1, "fact", "Likes football", 1), "bybit"))
        assertTrue(MemoryRanking.score(mem(2, "project", "Bybit swing trading", 1), "bybit") > 0)
        assertNotNull(MemoryRanking.tokens("The quick brown fox"))
    }
}
