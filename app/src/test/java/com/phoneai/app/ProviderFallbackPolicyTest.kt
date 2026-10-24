package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ProviderFallbackPolicyTest {
    @Test fun onlyExplicitHttp429AllowsFallback() {
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 429", true)))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 500", false)))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(IllegalStateException("invalid model")))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(IOException("network unavailable")))
    }
}
