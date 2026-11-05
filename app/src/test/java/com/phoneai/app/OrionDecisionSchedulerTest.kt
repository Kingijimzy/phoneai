package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OrionDecisionSchedulerTest {
    @Test fun windowQueueKeyNeverContainsTheForegroundPackageIdentifier() {
        val key = OrionDecisionScheduler.WINDOW_EVENT_KEY
        assertEquals("window-transition", key)
        assertFalse(key.contains("com."))
        assertFalse(key.contains("whatsapp"))
        assertFalse(key.contains("bybit"))
    }
}
