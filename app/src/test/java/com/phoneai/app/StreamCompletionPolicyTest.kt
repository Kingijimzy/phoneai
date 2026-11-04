package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamCompletionPolicyTest {
    @Test fun completeTerminalEventAcceptsVisibleResponse() {
        assertTrue(StreamCompletionPolicy.isComplete(true, true, false))
    }

    @Test fun eofAfterPartialTextIsTreatedAsInterrupted() {
        assertFalse(StreamCompletionPolicy.isComplete(true, false, false))
    }

    @Test fun userCancellationNeverCountsAsCompleteEvenAfterText() {
        assertFalse(StreamCompletionPolicy.isComplete(true, true, true))
        assertFalse(StreamCompletionPolicy.isComplete(true, false, true))
    }

    @Test fun emptyNonStreamingFallbackRemainsEligibleForBodyParsing() {
        assertTrue(StreamCompletionPolicy.isComplete(false, false, false))
    }
}
