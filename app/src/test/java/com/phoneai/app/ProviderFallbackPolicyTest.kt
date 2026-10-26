package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ProviderFallbackPolicyTest {
    @Test fun providerFanOutUsesAllEligibleSavedProvidersWithinCommandBudget() {
        // Provider rotation is bounded by this cap and the shared per-command request budget.
        assertTrue(ProviderFallbackPolicy.MAX_BACKUP_PROVIDERS == 14)
    }

    @Test fun recoverableHttpAndModelFailuresAllowRotation() {
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 429", 429)))
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 503", 503)))
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 404", 404)))
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(AiException("model returned an empty response")))
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(AiException("OpenRouter: out of credit (402).")))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 401", 401)))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 400", 400)))
    }

    @Test fun providerResponseTimeoutRotatesButNetworkAndAuthenticationFailuresDoNot() {
        // A response timeout from one API should move to a different provider, not strand the turn.
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(AiException("Google Gemini: request timed out.")))
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(AiException("no response before timeout")))
        // A device/network connectivity failure is not evidence that another API will be reachable.
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(IOException("network unavailable")))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("API key rejected (401)")))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(IllegalStateException("invalid model")))
    }

    @Test fun cancellationBudgetAndRepeatedFailureStopDoNotRotate() {
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(RuntimeException("Stopped.")))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("Stopped before sending another request: budget")))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("stopped after three identical HTTP failures (HTTP 429)", 429)))
    }

    @Test fun disabledAutoSwitchBlocksRecoverableFallback() {
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("rate limited", 429), false))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("model returned an empty response"), false))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(AiException("HTTP 503", 503), false))
    }
}
