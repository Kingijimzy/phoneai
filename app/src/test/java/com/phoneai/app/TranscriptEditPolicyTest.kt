package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptEditPolicyTest {
    @Test fun editingEarlierTurnRemovesItsOldAnswerAndAllLaterTurns() {
        val transcript = listOf("me" to "first", "ai" to "answer 1", "me" to "second", "ai" to "answer 2")
        assertEquals(listOf("me" to "first", "ai" to "answer 1"), TranscriptEditPolicy.rewind(transcript, "second"))
    }

    @Test fun retryAfterPreviousResponseRemovesLatestMatchingTurnAndResponse() {
        val transcript = listOf("me" to "repeat", "ai" to "old answer", "me" to "repeat", "ai" to "newer answer")
        assertEquals(listOf("me" to "repeat", "ai" to "old answer"), TranscriptEditPolicy.rewind(transcript, "repeat"))
    }

    @Test fun missingPromptDoesNotMutateOrInventTranscript() {
        val transcript = listOf("me" to "saved", "ai" to "reply")
        assertNull(TranscriptEditPolicy.rewind(transcript, "not present"))
        assertEquals(listOf("me" to "saved", "ai" to "reply"), transcript)
    }

    @Test fun rewindDoesNotRetainInvalidOrBlankTranscriptRows() {
        val transcript = listOf("me" to "first", "system" to "hidden", "ai" to "answer", "me" to "edit", "tool" to "secret")
        assertEquals(listOf("me" to "first", "ai" to "answer"), TranscriptEditPolicy.rewind(transcript, "edit"))
    }
}
