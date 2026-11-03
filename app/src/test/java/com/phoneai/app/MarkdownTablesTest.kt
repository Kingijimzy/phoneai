package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class MarkdownTablesTest {
    @Test fun tableBecomesBullets() {
        val t = "Sessions:\n| Session | Hours | Note |\n|---|---|---|\n| London | 08-17 | High volume |\n| New York | 13-22 | Strong |\n\nDone."
        val r = MarkdownTables.flatten(t)
        assertEquals("Sessions:\n**Session · Hours · Note**\n- **London** — 08-17 · High volume\n- **New York** — 13-22 · Strong\n\nDone.", r)
    }
    @Test fun plainTextAndSingleBarsUntouched() {
        assertEquals("a | b is fine", MarkdownTables.flatten("a | b is fine"))
        assertEquals("no tables here", MarkdownTables.flatten("no tables here"))
    }
}
