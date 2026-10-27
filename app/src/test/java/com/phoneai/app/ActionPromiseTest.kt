package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class ActionPromiseTest {
    @Test fun announcedStepWithoutToolIsCaught() {
        assertTrue(ActionPromise.announcesUnfinishedStep("I'll check public GitHub info for phoneai.", 0))
        assertTrue(ActionPromise.announcesUnfinishedStep("Let me look up the repository.", 0))
        assertTrue(ActionPromise.announcesUnfinishedStep("ACTION", 0))
    }

    @Test fun finishedAnswersAreNotNudged() {
        assertFalse(ActionPromise.announcesUnfinishedStep("The repo has 3 open issues and was last pushed today.", 0))
        assertFalse(ActionPromise.announcesUnfinishedStep("Do you want me to check the repo?", 0))
    }

    @Test fun afterToolCallsNothingIsNudged() {
        assertFalse(ActionPromise.announcesUnfinishedStep("I'll check the next one.", 2))
    }

    @Test fun emptyTextIsNotNudged() {
        assertFalse(ActionPromise.announcesUnfinishedStep("   ", 0))
    }
}
