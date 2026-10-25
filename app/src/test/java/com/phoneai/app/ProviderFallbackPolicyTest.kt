package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ProviderFallbackPolicyTest {
    @Test fun onlyExplicitHttp429AllowsFallback() {
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 429", 429)))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 429 text without confirmed status")))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 500", 500)))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 503", 503)))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(IllegalStateException("invalid model")))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(IOException("network unavailable")))
    }
}
