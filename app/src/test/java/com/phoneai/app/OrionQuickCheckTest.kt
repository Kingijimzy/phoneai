package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionQuickCheckTest {
    @Test fun slowFirstWordIsFlagged() {
        assertEquals(OrionQuickCheck.Level.PASS, OrionQuickCheck.speedRow("A", 3000.0).level)
        val slow = OrionQuickCheck.speedRow("B", 80_000.0)
        assertEquals(OrionQuickCheck.Level.WARN, slow.level)
        assertTrue(slow.note.contains("80s"))
        assertEquals(OrionQuickCheck.Level.WARN, OrionQuickCheck.speedRow("C", null).level)
    }
    @Test fun failoverNeedsTwoRoutes() {
        assertEquals(OrionQuickCheck.Level.FAIL, OrionQuickCheck.failoverRow(0, emptyList()).level)
        assertEquals(OrionQuickCheck.Level.WARN, OrionQuickCheck.failoverRow(1, emptyList()).level)
        assertEquals(OrionQuickCheck.Level.WARN, OrionQuickCheck.failoverRow(3, listOf("NIM")).level)
        assertEquals(OrionQuickCheck.Level.PASS, OrionQuickCheck.failoverRow(3, emptyList()).level)
    }
    @Test fun summaryCountsProblems() {
        val rows = listOf(OrionQuickCheck.Row("g", "a", OrionQuickCheck.Level.PASS, ""), OrionQuickCheck.Row("g", "b", OrionQuickCheck.Level.FAIL, ""), OrionQuickCheck.Row("g", "c", OrionQuickCheck.Level.WARN, ""))
        assertEquals("1 failing, 1 degraded", OrionQuickCheck.summary(rows))
        assertEquals("all 1 checks passed", OrionQuickCheck.summary(rows.take(1)))
        assertTrue(OrionQuickCheck.render(rows).startsWith("QUICK CHECK: 1 failing"))
    }
}
