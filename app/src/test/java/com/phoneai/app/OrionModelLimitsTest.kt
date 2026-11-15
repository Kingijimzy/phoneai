package com.phoneai.app

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OrionModelLimitsTest {
    @After fun reset() = OrionModelLimits.clearForTest()

    @Test fun geminiCatalogEntryIsParsed() {
        val l = OrionModelLimits.parseCatalogEntry(JSONObject("""{"name":"models/gemini-3.1-pro","inputTokenLimit":1048576,"outputTokenLimit":65536}"""))
        assertEquals(1048576, l.context); assertEquals(65536, l.output)
    }

    @Test fun openAiStyleEntriesAreParsed() {
        val l = OrionModelLimits.parseCatalogEntry(JSONObject("""{"id":"x","context_length":262144,"top_provider":{"max_completion_tokens":32768}}"""))
        assertEquals(262144, l.context); assertEquals(32768, l.output)
    }

    @Test fun errorTextGivesOutputLimit() {
        val l = OrionModelLimits.parseCatalogEntry(JSONObject("{}"))
        assertNull(l.context)
        val e = OrionModelLimits.parseErrorText("Invalid max_tokens value, the valid range of max_tokens is [1, 8192]")
        assertEquals(8192, e?.output)
    }

    @Test fun errorTextGivesContextLimit() {
        val e = OrionModelLimits.parseErrorText("This model's maximum context length is 131072 tokens. However, you requested 200000")
        assertEquals(131072, e?.context)
        assertNull(OrionModelLimits.parseErrorText("bad request"))
    }

    @Test fun learnedOverridesStaticAndFeedsPolicy() {
        val before = OrionProviderContextPolicy.capabilities("deepseek", "deepseek-v4-pro")
        assertEquals(384_000, before.maxOutputTokens)
        OrionModelLimits.record("deepseek", "deepseek-v4-pro", null, 8192)
        val after = OrionProviderContextPolicy.capabilities("deepseek", "deepseek-v4-pro")
        assertEquals(8192, after.maxOutputTokens)
        assertEquals(1_000_000, after.contextTokens)
    }

    @Test fun geminiAndNemotronHaveLargeStaticLimits() {
        assertEquals(65_536, OrionProviderContextPolicy.capabilities("gemini", "gemini-3.1-pro").maxOutputTokens)
        assertNotNull(OrionModelLimits.static("nvidia", "nvidia/nemotron-3-super"))
    }
}
