package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ProviderFallbackPolicyTest {
    @Test fun onlyHttp429AllowsOneBackupAttempt() {
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 429", 429)))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 503", 503)))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 401", 401)))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 404", 404)))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 400", 400)))
    }

    @Test fun networkTimeoutEmptyReplyAndOtherFailuresDoNotRotate() {
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("Groq: request timed out.")))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("model returned an empty response")))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(IOException("network unavailable")))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(IllegalStateException("invalid model")))
    }

    @Test fun cancellationBudgetAndRepeatedFailureStopDoNotRotate() {
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(RuntimeException("Stopped.")))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("Stopped before sending another request: budget")))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("stopped after three identical HTTP failures (HTTP 429)", 429)))
    }
}
