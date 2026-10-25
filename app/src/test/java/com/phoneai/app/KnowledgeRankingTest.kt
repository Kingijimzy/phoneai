package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeRankingTest {
    @Test fun hybridRankPutsTheMatchingPassageFirst() {
        val docs = listOf(
            "Jollof rice recipe needs tomatoes and pepper",
            "Bybit swing trading plan uses the four hour chart and strict stop loss",
            "Football fixtures for the weekend"
        )
        assertEquals(docs[1], MemoryRanking.hybridRank(docs, { it }, "what is my bybit trading stop loss plan").first())
    }

    @Test fun hybridRankReturnsNothingWithoutOverlap() {
        assertTrue(MemoryRanking.hybridRank(listOf("alpha beta gamma"), { it }, "zzz yyy").isEmpty())
        assertTrue(MemoryRanking.hybridRank(emptyList<String>(), { it }, "anything here").isEmpty())
    }

    @Test fun chunkingKeepsParagraphsAndRespectsTheLimit() {
        val text = "First paragraph here.\n\nSecond paragraph here.\n\n" + "word ".repeat(300)
        val chunks = KnowledgeStore.chunk(text, 200)
        assertTrue(chunks.size >= 3)
        assertTrue(chunks.all { it.length <= 200 })
        assertTrue(chunks.first().contains("First paragraph"))
    }
}
