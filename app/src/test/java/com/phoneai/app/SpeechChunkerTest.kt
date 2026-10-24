package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechChunkerTest {
    @Test
    fun keepsSentenceBoundariesAndTerminalPunctuation() {
        assertEquals(
            listOf("First sentence.", "Second sentence?", "Third!"),
            SpeechChunker.split("First sentence.  Second sentence?\nThird!")
        )
    }

    @Test
    fun longTextIsSplitIntoBoundedNonEmptyChunksWithoutDroppingPunctuation() {
        val input = ("one, two; three four five six seven eight nine ten ").repeat(8).trim()
        val chunks = SpeechChunker.split(input, limit = 24)

        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.isNotBlank() && it.length <= 24 })
        assertEquals(input, chunks.joinToString(" "))
        assertTrue(chunks.any { it.endsWith(",") || it.endsWith(";") })
    }

    @Test
    fun returnsNoChunksForBlankText() {
        assertTrue(SpeechChunker.split(" \n\t ").isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidChunkLimit() {
        SpeechChunker.split("hello", limit = 0)
    }

    @Test
    fun normalizesWhitespaceAcrossChunks() {
        val result = SpeechChunker.split("  hello\n\tworld.  How  are   you? ")
        assertFalse(result.any { it.contains(Regex("\\s{2,}")) })
        assertEquals(listOf("hello world.", "How are you?"), result)
    }
}
