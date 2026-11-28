package com.phoneai.app

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
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

    @Test fun promptBlockIsEmptyWithNoStoredSummary() {
        val c = RuntimeEnvironment.getApplication() as Context
        c.getSharedPreferences("orion_core_summary_v1", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals("", OrionMemoryCompactor.promptBlock(c))
    }

    @Test fun promptBlockMarksAStoredSummaryAsBackgroundNotAStatusReportToRecite() {
        // The real bug this guards against: this block is injected into every single turn with no
        // per-message relevance filter (unlike OrionExperience.requestBlock), so a model anchors on
        // it and volunteers it unprompted - a casual "wassup" came back reciting three known bugs
        // and a battery reading because that's what the stored summary happened to hold.
        val c = RuntimeEnvironment.getApplication() as Context
        c.getSharedPreferences("orion_core_summary_v1", Context.MODE_PRIVATE).edit()
            .putString("text", "keyboard select broken; YouTube Music playback broken").commit()
        val block = OrionMemoryCompactor.promptBlock(c)
        assertTrue(block.contains("keyboard select broken"))
        assertTrue(block.contains("not a report to recite"))
        assertTrue(block.contains("casual message"))
    }
}
