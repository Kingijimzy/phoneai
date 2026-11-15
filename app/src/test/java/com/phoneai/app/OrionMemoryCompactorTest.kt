package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionMemoryCompactorTest {
    @Test fun dueOnlyWhenEnoughRawAndAnHourPassed() {
        assertFalse(OrionMemoryCompactor.due(10_000_000, 9_000_000, 5000))
        assertFalse(OrionMemoryCompactor.due(10_000_000, 0, 100))
        assertTrue(OrionMemoryCompactor.due(10_000_000, 0, 5000))
    }
    @Test fun clampKeepsWithinBudgetOnLineBoundary() {
        val long = (1..400).joinToString("\n") { "line $it of the summary" }
        val out = OrionMemoryCompactor.clamp(long)
        assertTrue(out.length <= OrionMemoryCompactor.SUMMARY_MAX_CHARS)
        assertTrue(out.lines().last().matches(Regex("line \\d+ of the summary")))
        assertEquals("short", OrionMemoryCompactor.clamp("  short "))
    }
    @Test fun promptCarriesPreviousAndRaw() {
        val p = OrionMemoryCompactor.prompt("OLD", "NEWLOG")
        assertTrue(p.contains("OLD") && p.contains("NEWLOG") && p.contains("2000"))
    }
}
