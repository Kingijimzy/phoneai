package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTest {
    @Test fun normalizesUnorderedListsToReadableBullets() {
        assertEquals("• first\n• second", Markdown.normalizeLines("- first\n* second"))
    }

    @Test fun normalizesOrderedListsWithoutKeepingNumberMarkers() {
        assertEquals("• first\n• second\n• third", Markdown.normalizeLines("1. first\n2) second\n   3. third"))
    }

    @Test fun preservesHeadingsAndOrdinaryParagraphs() {
        assertEquals("# Heading\nA normal paragraph.", Markdown.normalizeLines("# Heading\nA normal paragraph."))
    }

    @Test fun emptyLinesRemainEmpty() {
        assertEquals("one\n\ntwo", Markdown.normalizeLines("one\n\ntwo"))
    }
}
