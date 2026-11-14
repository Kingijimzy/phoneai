package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionDiffCardTest {
    private val diff = "--- a/src/Main.kt\n+++ b/src/Main.kt\n@@ -1,2 +1,2 @@\n-old\n+new\n+extra\n same"
    @Test fun detectsUnifiedDiff() { assertTrue(looksLikeUnifiedDiff(diff)); assertFalse(looksLikeUnifiedDiff("val a = -1\n+ b")) }
    @Test fun countsChangesAndFile() {
        val s = orionDiffStats(diff)
        assertEquals(2, s.added); assertEquals(1, s.removed); assertEquals("src/Main.kt", s.file)
    }
}
