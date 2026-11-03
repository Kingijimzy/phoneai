package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class BrowserResearchSecurityTest {
    @Test fun rejectsPlainHttpBeforeOpeningAnyConnection() {
        val result = BrowserResearch.fetch("http://example.com/")
        assertFalse(result.isSuccess)
        assertTrue(result.error.orEmpty().contains("HTTPS"))
    }

    @Test fun rejectsNonWebSchemesBeforeOpeningAnyConnection() {
        val result = BrowserResearch.fetch("file:///etc/passwd")
        assertFalse(result.isSuccess)
    }

    @Test fun cancellationIsHonoredBeforeNetworkAccess() {
        val cancelled = AtomicBoolean(true)
        val result = BrowserResearch.fetch("https://example.com/", cancelled)
        assertFalse(result.isSuccess)
        assertEqualsCancelled(result.error)
    }

    private fun assertEqualsCancelled(error: String?) {
        org.junit.Assert.assertEquals("Cancelled.", error)
    }
}
