package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesAdoptionTest {
    @Test fun detectsLongRepeatedModelOutput() {
        val fragment = "This is a repeated model response fragment with enough characters to exceed the detection window. "
        assertTrue(HermesRepetitionGuard.isRepetitionDominated(fragment.repeat(8)))
        assertTrue(HermesRepetitionGuard.isRunawayRepetition(fragment.repeat(8)))
    }

    @Test fun doesNotFlagShortOrDistinctOutput() {
        assertFalse(HermesRepetitionGuard.isRepetitionDominated("repeat ".repeat(20)))
        val distinct = (1..20).joinToString("\n") { "row $it: a distinct result with a unique value $it" }
        assertFalse(HermesRepetitionGuard.isRunawayRepetition(distinct))
    }

    @Test fun truncationPreservesHeadAndTailWithExplicitNotice() {
        val input = "HEAD-" + "x".repeat(100) + "-TAIL"
        val result = HermesToolOutputTruncation.truncateHeadTail(input, 40, "TEST")
        assertTrue(result.startsWith("HEAD-"))
        assertTrue(result.contains("TEST TRUNCATED"))
        assertTrue(result.endsWith("-TAIL"))
    }

    @Test fun truncationLeavesShortOutputUnchanged() {
        assertEquals("small output", HermesToolOutputTruncation.truncateHeadTail("small output", 40))
    }
}
