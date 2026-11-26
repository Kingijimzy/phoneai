package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OrionVoiceTest {
    @Test fun stockOpenersAreFilteredOutEvenTheOnesAddedAfterTheOwnerFlaggedThem() {
        listOf(
            "On it.", "on it", "Doing it", "doing it.", "One second.", "one sec",
            "Working on it!", "Let me think...", "Got it.", "Sure.", "Okay."
        ).forEach { phrase ->
            assertNull("'$phrase' is a stock phrase and must not be shown as the opener", OrionVoice.ownWords(phrase))
        }
    }

    @Test fun aRealOpenerStillComesThrough() {
        assertEquals("Checking the last three commits for what changed.",
            OrionVoice.ownWords("Checking the last three commits for what changed. I'll pull the diff next."))
    }

    @Test fun lineFallsBackToTheRealActionWhenTheTurnTextIsJustAStockPhrase() {
        val args = org.json.JSONObject().put("path", "/sdcard/notes.txt")
        assertEquals("read_file: /sdcard/notes.txt", OrionVoice.line("One second.", "read_file", args))
    }
}
