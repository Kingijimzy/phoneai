package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun fencedCodeKeepsSourceReadableWithoutFenceMarkersOrExtraBlankLines() {
        val rendered = Markdown.render("Before\n```kotlin\nval answer = 42\nprintln(answer)\n```\nAfter").toString()
        assertEquals("Before\nval answer = 42\nprintln(answer)\nAfter", rendered)
    }

    @Test fun inlineCodeAndBoldKeepTheirReadableText() {
        assertEquals("Use code and bold text.", Markdown.render("Use `code` and **bold** text.").toString())
    }

    @Test fun markdownLinksShowTheirLabelAndHideDestinationSyntax() {
        assertEquals("Open the build.", Markdown.render("Open [the build](https://example.com/build).").toString())
    }

    @Test fun unclosedCodeFenceStillRendersItsContents() {
        assertEquals("val x = 1", Markdown.render("```kotlin\nval x = 1").toString())
    }

    @Test fun renderingEmptyTextIsSafe() {
        assertTrue(Markdown.render("").toString().isEmpty())
    }
}
