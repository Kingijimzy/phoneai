package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ProviderFallbackPolicyTest {
    @Test fun backupCountRemainsBounded() {
        assertTrue(ProviderFallbackPolicy.MAX_BACKUP_PROVIDERS == 2)
    }

    @Test fun eligibleTransientProviderFailuresAllowBoundedRotation() {
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 429", 429)))
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(AiException("rate limit reached", 429)))
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 503", 503)))
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(AiException("Google Gemini: request timed out.")))
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(AiException("provider timed out")))
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(AiException("model returned an empty response")))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 404", 404)))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("OpenRouter: out of credit (402).")))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 429 without a parsed status")))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 401", 401)))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 400", 400)))
    }

    @Test fun networkAuthenticationAndCancellationFailuresNeverRotate() {
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(IOException("network unavailable")))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("API key rejected (401)", 401)))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(IllegalStateException("invalid model")))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(InterruptedException("cancelled")))
    }

    @Test fun cancelledBudgetAndRepeatedFailureStopDoNotRotateEvenFor429() {
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(RuntimeException("Stopped.")))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("Stopped before sending another request: budget")))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("stopped after three identical HTTP failures (HTTP 429)", 429)))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 429", 429), false))
    }

    @Test fun disabledAutoSwitchBlocksFallback() {
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 429", 429), false))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("model returned an empty response"), false))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 503", 503), false))
    }
}
