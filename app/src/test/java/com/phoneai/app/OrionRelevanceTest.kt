package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionRelevanceTest {
    @Test fun stemUndoesADoubledConsonantInsteadOfLeavingItIn() {
        // The crude stemmer this replaced (OrionToolRouter's old private one) turned "running" into
        // "runn" by just chopping "ing"; the correct stem is "run".
        assertEquals("run", OrionRelevance.stem("running"))
        assertEquals("plan", OrionRelevance.stem("planning"))
    }

    @Test fun stemFoldsIrregularPluralsCorrectly() {
        assertEquals("company", OrionRelevance.stem("companies"))
    }

    @Test fun stemLeavesShortOrAlreadyBareWordsAlone() {
        assertEquals("gas", OrionRelevance.stem("gas"))
        assertEquals("bus", OrionRelevance.stem("bus"))
        assertEquals("open", OrionRelevance.stem("open"))
    }

    @Test fun tokensMatchAcrossWordFormsNotJustLiteralSpelling() {
        // The real point of unifying on one stemmer: "building" and "builds" should read as the same
        // concept, where a literal word-overlap check would miss it.
        assertTrue(OrionRelevance.overlap("I'm building a new feature", "the build keeps failing"))
        assertTrue(OrionRelevance.overlap("planning the trip for next week", "plans change often"))
    }

    @Test fun noOverlapWhenNothingIsShared() {
        assertFalse(OrionRelevance.overlap("what's my battery at", "the calendar sync keeps failing"))
    }

    @Test fun blankTextHasNoTokensAndNoOverlap() {
        assertTrue(OrionRelevance.tokens("").isEmpty())
        assertFalse(OrionRelevance.overlap("", "anything at all"))
    }
}
