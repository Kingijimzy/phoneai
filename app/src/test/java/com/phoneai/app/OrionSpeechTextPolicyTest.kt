package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionSpeechTextPolicyTest {
    @Test fun keepsNaturalProseAndMarkdownLinkLabel() {
        assertEquals(
            "Good morning. I checked the voice model. It is ready.",
            OrionSpeechTextPolicy.prepare("## Good morning\nI checked the [voice model](https://example.com). It is **ready**.")
        )
    }

    @Test fun neverReadsFencedOrInlineSourceCode() {
        val fence = "\u0060".repeat(3)
        val spoken = OrionSpeechTextPolicy.prepare(
            "Here is the result.\n\n" + fence + "kotlin\nfun main() { println(\"secret\") }\n" + fence + "\nThe fix is complete."
        )
        assertTrue(spoken.contains("Here is the result"))
        assertTrue(spoken.contains("The fix is complete"))
        assertTrue(!spoken.contains("println"))
        assertTrue(!spoken.contains("secret"))
    }

    @Test fun dropsRawStructuredPayloadsAndInternalExecutionLines() {
        assertEquals("", OrionSpeechTextPolicy.prepare("{\"tool\":\"shell\",\"command\":\"rm -rf\"}"))
        val spoken = OrionSpeechTextPolicy.prepare(
            "The task is complete.\nassistant to=terminal.exec\nTool result: exit code 0\nThe file was saved."
        )
        assertEquals("The task is complete. The file was saved.", spoken)
    }

    @Test fun removesUrlsAndTableRowsInsteadOfReadingRawMarkup() {
        val spoken = OrionSpeechTextPolicy.prepare(
            "Read the report at https://example.com/report.\n| Name | Value |\n|---|---|\nThe result is positive."
        )
        assertTrue(!spoken.contains("https://"))
        assertTrue(!spoken.contains("|"))
        assertTrue(spoken.contains("The result is positive"))
    }
}
