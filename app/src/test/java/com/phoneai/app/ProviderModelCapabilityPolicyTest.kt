package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class ProviderModelCapabilityPolicyTest {
    @Test fun multimodalGeminiModelRemainsAvailableForTextChat() {
        val ids = listOf("gemini-4-carbon:free", "gemini-3.8-flash", "qwen2.5-vl-72b-instruct", "llava-1.6")
        assertEquals(
            listOf("gemini-4-carbon:free", "gemini-3.8-flash"),
            ProviderModelCapabilityPolicy.textCandidates(ids)
        )
    }

    @Test fun customEndpointCatalogueRefreshesAfterModelFailureInQuotaSaverMode() {
        assertTrue(ProviderModelCapabilityPolicy.shouldDiscoverAfterFailure("custom_repa", true))
        assertTrue(ProviderModelCapabilityPolicy.shouldDiscoverAfterFailure("nvidia", true))
        assertFalse(ProviderModelCapabilityPolicy.shouldDiscoverAfterFailure("groq", true))
        assertTrue(ProviderModelCapabilityPolicy.shouldDiscoverAfterFailure("groq", false))
    }

    @Test fun onlyExplicitVisionOnlyFamiliesAreExcludedFromTextCandidates() {
        val ids = listOf("google/gemma-3-27b-it:free", "meta-llama/llama-4-maverick", "foo/vision-only")
        assertEquals(
            listOf("google/gemma-3-27b-it:free", "meta-llama/llama-4-maverick"),
            ProviderModelCapabilityPolicy.textCandidates(ids)
        )
    }
}
