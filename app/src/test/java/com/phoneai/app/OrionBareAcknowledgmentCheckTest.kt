package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionBareAcknowledgmentCheckTest {
    @Test fun catchesTheExactPhrasesTheOwnerScreenshotted() {
        listOf("On it.", "Doing it", "One second.", "one sec.", "Working on it.", "Got it!")
            .forEach { assertTrue("'$it' is a bare acknowledgment with nothing behind it",
                OrionBareAcknowledgmentCheck.isBareAcknowledgment(it)) }
    }

    @Test fun aRealAnswerIsNeverFlagged() {
        assertFalse(OrionBareAcknowledgmentCheck.isBareAcknowledgment(
            "Straight answer: I'm better at diagnosing myself than fixing myself right now."))
        assertFalse(OrionBareAcknowledgmentCheck.isBareAcknowledgment(
            "I don't have a self-model yet; that's the honest gap."))
    }

    @Test fun blankOrEmptyTextIsNotTreatedAsABareAcknowledgment() {
        // An empty turn is a different failure mode (no answer at all); this check is only for a
        // stock phrase standing in as the whole reply, not for silence.
        assertFalse(OrionBareAcknowledgmentCheck.isBareAcknowledgment(""))
        assertFalse(OrionBareAcknowledgmentCheck.isBareAcknowledgment("   "))
    }
}
