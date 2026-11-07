package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionEmbeddingTest {
    @Test fun vectorsAreUnitLengthAndStable() {
        val v = OrionEmbedding.embed("My favourite team is Real Madrid")
        assertEquals(OrionEmbedding.DIM, v.size)
        assertEquals(1.0, OrionEmbedding.cosine(v, v), 1e-4)
        assertEquals(OrionEmbedding.similarity("a b c d", "a b c d"), OrionEmbedding.similarity("a b c d", "a b c d"), 0.0)
    }

    @Test fun paraphrasesBeatUnrelatedText() {
        assertTrue(OrionEmbedding.similarity("My favourite team is Real Madrid", "which club do I support") >
            OrionEmbedding.similarity("I love jollof rice for dinner", "which club do I support"))
        assertTrue(OrionEmbedding.similarity("I live in Lagos", "where is my home city") >
            OrionEmbedding.similarity("I trade swing positions on Bybit", "where is my home city"))
    }

    @Test fun rankPutsTheParaphraseFirstAndDropsNoise() {
        val facts = listOf("I love jollof rice for dinner", "My favourite team is Real Madrid", "I trade swing positions on Bybit")
        val ranked = OrionEmbedding.rank(facts, { it }, "which club do I support")
        assertEquals("My favourite team is Real Madrid", ranked.first().first)
        assertTrue(ranked.none { it.first.contains("Bybit") })
    }
}
