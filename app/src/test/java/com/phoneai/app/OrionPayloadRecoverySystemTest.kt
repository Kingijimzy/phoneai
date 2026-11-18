package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionPayloadRecoverySystemTest {
    private fun msgs() = JSONArray().put(JSONObject().put("role", "system").put("content", "S".repeat(40_000)))
        .put(JSONObject().put("role", "user").put("content", "hello"))

    @Test fun firstPassKeepsSystemPromptWhole() {
        assertEquals(40_000, OrionProviderPayloadRecovery.compact(msgs(), 1).getJSONObject(0).getString("content").length)
    }

    @Test fun laterPassesShortenSystemPromptButKeepTheRequest() {
        val third = OrionProviderPayloadRecovery.compact(msgs(), 3)
        assertTrue(third.getJSONObject(0).getString("content").length < 7_000)
        assertEquals("hello", third.getJSONObject(1).getString("content"))
    }
}
