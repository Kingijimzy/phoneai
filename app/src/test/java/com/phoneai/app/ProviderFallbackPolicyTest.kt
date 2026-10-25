package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ProviderFallbackPolicyTest {
    @Test fun providerFailuresRotate() {
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 429", 429)))
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 503", 503)))
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(AiException("Groq: request timed out.")))
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(AiException("model returned an empty response")))
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(IOException("network unavailable")))
    }

    @Test fun cancelBudgetAndUnknownErrorsDoNotRotate() {
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(RuntimeException("Stopped.")))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("Stopped before sending another request: budget")))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(IllegalStateException("invalid model")))
    }
}
