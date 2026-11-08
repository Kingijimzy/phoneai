package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionHonestyTest {
    @Test fun inventedToolResultsAreRemoved() {
        val out = OrionHonesty.scrub("dev_shell executed `pip install requests`. It reported success. The page title is Example.", emptySet())
        assertFalse(out, out.contains("dev_shell executed"))
        assertTrue(out.contains("Correction"))
    }

    @Test fun realToolResultsAreKept() {
        val text = "dev_shell executed the command and printed Python 3.11."
        assertEquals(text, OrionHonesty.scrub(text, setOf("dev_shell")))
    }
}
