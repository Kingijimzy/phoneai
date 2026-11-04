package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Test

class OrionProgressStepsTest {
    @Test fun collapsesRepeatedStepsKeepingTheLatestState() {
        val steps = OrionActivityTimeline.progressSteps(listOf(
            "RUNNING · Reading screen: looking", "DONE · Reading screen: ok",
            "RUNNING · Opening Settings: tap", "garbage line"
        ))
        assertEquals(listOf("Reading screen" to "DONE", "Opening Settings" to "RUNNING"), steps.map { it.title to it.state })
    }
    @Test fun emptyEvidenceGivesNoSteps() { assertEquals(0, OrionActivityTimeline.progressSteps(emptyList()).size) }
}
