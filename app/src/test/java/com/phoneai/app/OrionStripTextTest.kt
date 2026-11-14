package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionStripTextTest {
    @Test fun taskMarkersAreRemoved() {
        val out = OrionStripText.clean("[TASK:RESULT] save_file — FILE SAVED AND CONTENT VERIFIED: dog_image_request.txt | Downloads/Downloads/")
        assertFalse(out.contains("[TASK"))
        assertTrue(out.startsWith("Save file: Saved: dog_image_request.txt"))
        assertTrue(out.contains(" · "))
    }
    @Test fun plainTextPassesThroughAndIsBounded() {
        assertEquals("Thinking...", OrionStripText.clean("Thinking..."))
        assertTrue(OrionStripText.clean("x".repeat(500)).length <= 140)
    }
}
