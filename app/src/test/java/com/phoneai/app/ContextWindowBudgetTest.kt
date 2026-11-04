package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class ContextWindowBudgetTest {
    @Test fun reportsSectionsClippedByPerSectionCap() {
        val packed = ContextWindowBudget.pack(listOf("ORION CENTRAL BRAIN\\n" + "x".repeat(2_000)), maxTokens = 100)
        assertEquals(1, packed.includedSections)
        assertEquals(1, packed.truncatedSections)
        assertTrue(packed.text.length < 2_000)
    }

    @Test fun reportsLowerPrioritySectionsOmittedWhenBudgetIsConsumed() {
        val packed = ContextWindowBudget.pack(listOf(
            "TASK RESUME INSTRUCTIONS\\n" + "a".repeat(1_000),
            "GENERAL LOW PRIORITY CONTEXT\\n" + "b".repeat(1_000)
        ), maxTokens = 100)
        assertEquals(1, packed.includedSections)
        assertEquals(1, packed.omittedSections)
        assertTrue(packed.omittedSectionLabels.any { it.contains("GENERAL LOW PRIORITY") })
    }

    @Test fun emptyContextHasZeroedReceipt() {
        val packed = ContextWindowBudget.pack(listOf("", "  "), maxTokens = 20)
        assertEquals("", packed.text)
        assertEquals(0, packed.includedSections)
        assertEquals(0, packed.omittedSections)
        assertEquals(0, packed.truncatedSections)
    }

    @Test fun prioritizesTaskResumeOverGeneralContext() {
        val packed = ContextWindowBudget.pack(listOf(
            "GENERAL CONTEXT\\n" + "g".repeat(500), "TASK RESUME INSTRUCTIONS\\nresume"
        ), maxTokens = 200)
        assertTrue(packed.text.indexOf("TASK RESUME INSTRUCTIONS") >= 0)
    }
}
