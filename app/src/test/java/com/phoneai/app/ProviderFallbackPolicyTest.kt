package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ProviderFallbackPolicyTest {
    @Test fun backupCountRemainsBounded() {
        assertTrue(ProviderFallbackPolicy.MAX_BACKUP_PROVIDERS == 2)
    }

    @Test fun rateLimitsTimeoutsEmptyRepliesAndServerErrorsRotate() {
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 429", 429)))
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(AiException("rate limit reached", 429)))
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 503", 503)))
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 502", 502)))
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(AiException("Google Gemini: request timed out.")))
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(SocketTimeoutException("Read timed out")))
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(AiException("model returned an empty response")))
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(RuntimeException("provider temporarily unavailable (HTTP 503)")))
    }

    @Test fun authBillingOfflineAndNonTransientErrorsNeverRotate() {
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 404", 404)))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("OpenRouter: out of credit (402).")))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 401", 401)))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 400", 400)))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("API key rejected (401)", 401)))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(IllegalStateException("invalid model")))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(IOException("network unavailable")))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(UnknownHostException("host not found")))
    }

    @Test fun cancellationBudgetAndRepeatedFailureStopNeverRotate() {
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(InterruptedException("cancelled")))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(RuntimeException("Stopped.")))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("Stopped before sending another request: budget")))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("stopped after three identical HTTP failures (HTTP 429)", 429)))
    }

    @Test fun disabledAutoSwitchBlocksEveryFallback() {
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 429", 429), false))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("model returned an empty response"), false))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 503", 503), false))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(SocketTimeoutException("Read timed out"), false))
    }
}
