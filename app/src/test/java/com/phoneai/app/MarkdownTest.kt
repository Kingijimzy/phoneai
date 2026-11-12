package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JVM tests only: Android's Spannable implementation is exercised by the app runtime,
 * not by the Android SDK's throwing local-test stubs.
 */
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

    @Test fun markdownTablesBecomeReadableLabelValueRows() {
        val input = "| Pair | Bias | Status |\n| --- | --- | --- |\n| EURUSD | Bearish | Wait |\n| GBPUSD | Bullish | Confirmed |"
        assertEquals(
            "**Pair:** EURUSD · **Bias:** Bearish · **Status:** Wait\n**Pair:** GBPUSD · **Bias:** Bullish · **Status:** Confirmed",
            normalizeOrionMarkdownTables(input)
        )
    }

    @Test fun markdownTableConversionDoesNotRewriteCodeFences() {
        val input = "```text\n| a | b |\n| --- | --- |\n```"
        assertEquals(input, normalizeOrionMarkdownTables(input))
    }
    @Test fun orderedListsPreserveTheirNumbering() {
        assertEquals("1.  first\n2)  second", formatOrionMarkdown("1. first\n2) second").text)
    }

    @Test fun taskListsRenderCheckboxes() {
        assertEquals("☐  pending\n☑  completed", formatOrionMarkdown("- [ ] pending\n- [x] completed").text)
    }

    @Test fun blockquotesAndHorizontalRulesHaveReadableMarkers() {
        assertEquals("│  quoted text\n────────────────────────", formatOrionMarkdown("> quoted text\n---").text)
    }

    @Test fun fencedCodeShowsItsLanguageLabel() {
        assertEquals("PYTHON\nprint('hello')", formatOrionMarkdown("```python\nprint('hello')\n```").text)
    }

    @Test fun strikethroughUsesLineThroughSpan() {
        val formatted = formatOrionMarkdown("~~removed~~")
        assertEquals("removed", formatted.text)
        assertEquals(true, formatted.spanStyles.any {
            it.item.textDecoration == androidx.compose.ui.text.style.TextDecoration.LineThrough
        })
    }

    @Test fun emptyInputNormalizesSafely() {
        assertEquals("", Markdown.normalizeLines(""))
    }
}
