package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionRuntimeTest {
    @Test fun judgeAndEmbeddingModelsAreNeverChatModels() {
        assertTrue(ProviderModelCapabilityPolicy.mustEvict("some-judge-model"))
        assertTrue(ProviderModelCapabilityPolicy.mustEvict("text-embedding-3"))
        assertFalse(ProviderModelCapabilityPolicy.mustEvict("deepseek-chat"))
        assertTrue(ProviderModelCapabilityPolicy.isChatCapable("deepseek-v4.1-flash"))
        assertFalse(ProviderModelCapabilityPolicy.isChatCapable("gemini-live-2.5"))
    }

    @Test fun endpointMismatchBodiesAreRecognised() {
        assertFalse(ProviderModelCapabilityPolicy.isEndpointMismatch("""{"error":{"message":"missing field messages"}}"""))
    }

    @Test fun endpointMismatchRotatesProvidersButPlainBadRequestDoesNot() {
        val mismatch = AiException("deepseek: selected model x returned HTTP 400. Provider said: x is a judge model served only on POST /v1/systemone.", 400)
        val plain = AiException("deepseek: selected model x returned HTTP 400. Provider said: missing field.", 400)
        assertTrue(ProviderFallbackPolicy.shouldTryBackup(mismatch))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(plain))
        assertFalse(ProviderFallbackPolicy.shouldTryBackup(mismatch, autoSwitchEnabled = false))
    }

    @Test fun providerStateFollowsTheRecord() {
        fun h(ok: Int, failed: Int, consecutive: Int) = OrionProviderHealth.Health("p", ok, failed, consecutive, 0, 0, "")
        assertEquals("UNTESTED", h(0, 0, 0).state)
        assertEquals("HEALTHY", h(3, 1, 0).state)
        assertEquals("FLAKY", h(3, 2, 1).state)
        assertEquals("DOWN", h(0, 3, 3).state)
    }

    @Test fun graphReviewFindsAliasesAndStaysQuietOtherwise() {
        fun f(a: String, rel: String, b: String, n: Int = 2) = GraphFact(a, rel, b, n, 1L)
        val issues = OrionBrain.graphIssues(listOf(f("kingi", "lives in", "lagos"), f("kingi jim", "lives in", "lagos"), f("orion", "runs on", "poco f3")))
        assertEquals(1, issues.size)
        assertTrue(issues[0].contains("'kingi' and 'kingi jim'"))
        assertTrue(OrionBrain.graphIssues(listOf(f("orion", "runs on", "poco f3"), f("kingi", "likes", "football"))).isEmpty())
    }

    @Test fun subsystemsAreReachableThroughTheBrainTool() {
        assertEquals(ToolAccess.READ, ToolRegistry.access("brain"))
        assertTrue(ToolRegistry.find("brain")!!.promptLine.contains("providers"))
        assertTrue(ToolRegistry.find("brain")!!.promptLine.contains("Prometheus"))
    }
}
