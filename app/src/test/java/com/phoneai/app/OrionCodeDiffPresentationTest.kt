package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionCodeDiffPresentationTest {
    @Test fun detectsUnifiedDiffAndCountsOnlyChangedLines() {
        val patch = """--- a/src/Main.kt
+++ b/src/Main.kt
@@ -1,2 +1,3 @@
 fun main() {
-    println("old")
+    println("new")
+    println("extra")
 }"""
        val result = OrionCodeDiffPresentation.inspect("kotlin", patch)
        assertTrue(result.isDiff)
        assertEquals(2, result.additions)
        assertEquals(1, result.removals)
    }

    @Test fun explicitDiffLanguageWorksWithoutHunkHeaders() {
        val result = OrionCodeDiffPresentation.inspect("diff", "-old\n+new")
        assertTrue(result.isDiff)
        assertEquals(1, result.additions)
        assertEquals(1, result.removals)
    }

    @Test fun ordinaryCodeIsNotMisclassified() {
        val source = """val result = 2 + 2
println(result)
if (result > 3) println("yes")"""
        val result = OrionCodeDiffPresentation.inspect("kotlin", source)
        assertFalse(result.isDiff)
        assertEquals(0, result.additions)
        assertEquals(0, result.removals)
    }
}
