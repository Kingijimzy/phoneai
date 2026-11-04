package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ProviderFallbackPolicyTest {
    @Test fun backupCountRemainsBounded() {
        assertTrue(ProviderFallbackPolicy.MAX_BACKUP_PROVIDERS == 2)
    }

    @Test fun rotatesForRateLimitsTimeoutsAndRecoverableProviderFailures() {
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 429", 429)))
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(AiException("rate limit reached", 429)))
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(AiException("NVIDIA timed out waiting for model", 504)))
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(AiException("provider request timed out", 504)))
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(AiException("model returned an empty response", 502)))
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 413 request too large", 413)))
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 503", 503)))
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 404", 404)))
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 410", 410)))
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 422", 422)))
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 425", 425)))
    }

    @Test fun billingAuthenticationAndUnclassifiedFailuresNeverRotate() {
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("OpenRouter: out of credit (402).", 402)))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("API key rejected (401)", 401)))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 403", 403)))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 400", 400)))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("request timed out without a classified status")))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 429 without a parsed status")))
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

    @Test fun disabledAutoSwitchBlocksEveryFallbackClass() {
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 429", 429), false))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("model returned an empty response", 502), false))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 503", 503), false))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 413", 413), false))
    }
}
