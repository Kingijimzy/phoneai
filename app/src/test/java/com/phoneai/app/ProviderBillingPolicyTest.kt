package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderBillingPolicyTest {
    @Test fun paidProvidersAreBlockedByDefault() {
        assertFalse(ProviderBillingPolicy.isAllowed("openai", "gpt-4.1-mini", true, false))
        assertFalse(ProviderBillingPolicy.isAllowed("deepseek", "deepseek-chat", true, false))
    }

    @Test fun freeOpenRouterModelIsAllowedWithoutPaidOptIn() {
        assertTrue(ProviderBillingPolicy.isAllowed("openrouter", "meta-llama/llama:free", false, false))
    }

    @Test fun paidOpenRouterModelIsBlockedWithoutPaidOptIn() {
        assertFalse(ProviderBillingPolicy.isAllowed("openrouter", "meta-llama/llama", false, false))
    }

    @Test fun explicitOptInAllowsMeteredProviderAndModelFallbacks() {
        assertTrue(ProviderBillingPolicy.isAllowed("openai", "gpt-4.1-mini", true, true))
        assertTrue(ProviderBillingPolicy.isAllowed("openrouter", "some/model", false, true))
    }

    @Test fun localAndKnownFreeOnlyProvidersDoNotNeedPaidOptIn() {
        assertTrue(ProviderBillingPolicy.isAllowed("local", "llama3.2:3b", false, false))
        assertTrue(ProviderBillingPolicy.isAllowed("groq", "openai/gpt-oss-120b", false, false))
    }
}
