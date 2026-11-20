package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionHudErrorTest {
    @Test fun timeoutJsonIsTransientAndReadsAsOneSentence() {
        val raw = "coerena: {\"error\":{\"code\":408,\"message\":\"request body arrived too slowly\"}}"
        assertTrue(OrionChatFlood.isTransient(raw))
        val clean = OrionChatFlood.cleanError(raw)
        assertFalse(clean.contains("{"))
        assertTrue(clean.contains("408"))
    }
    @Test fun authFailureIsNotRetried() {
        assertFalse(OrionChatFlood.isTransient("rejected the key (401). Make a new key"))
    }
}
