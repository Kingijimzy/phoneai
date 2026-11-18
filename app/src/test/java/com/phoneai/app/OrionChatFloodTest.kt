package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionChatFloodTest {
    private fun line(id: Long, kind: String) = OrionChatLine(id = id, kind = kind, text = "x$id")

    @Test fun onlyTheNewestNotesOfARunStay() {
        val src = listOf(line(1, "me"), line(2, "note"), line(3, "note"), line(4, "note"), line(5, "ai"), line(6, "note"))
        assertEquals(listOf(1L, 3L, 4L, 5L, 6L), OrionChatFlood.fold(src).map { it.id })
    }

    @Test fun receiptsAreHidden() {
        val t = "page loaded\nLOCAL VERIFY PASS: visible state changed\nOBJECTIVE EVIDENCE RECEIPT: objective_id=main"
        assertEquals("page loaded", OrionChatFlood.cleanResult(t))
    }

    @Test fun htmlErrorPagesBecomeOneSentence() {
        val msg = "numes ai: error 522 <!DOCTYPE html> <html class=\"no-js\"> Tried 1 provider(s): numes ai."
        val out = OrionChatFlood.cleanError(msg)
        assertFalse(out.contains("<"))
        assertTrue(out.contains("HTTP 522"))
        assertTrue(out.startsWith("numes ai"))
    }

    @Test fun plainErrorsPassThroughTrimmed() {
        assertEquals("rate limited", OrionChatFlood.cleanError("  rate\n limited "))
    }
}
