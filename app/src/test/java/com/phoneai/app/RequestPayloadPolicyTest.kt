package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestPayloadPolicyTest {
    @Test fun keepsSystemAndNewestTurnWithinLimit() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "S".repeat(12000)))
            .put(JSONObject().put("role", "user").put("content", "old".repeat(3000)))
            .put(JSONObject().put("role", "assistant").put("content", "result".repeat(3000)))
            .put(JSONObject().put("role", "user").put("content", "latest request"))
        val bounded = RequestPayloadPolicy.bound(messages, maxTotalChars = 8000)
        var total = 0
        for (i in 0 until bounded.length()) total += bounded.getJSONObject(i).optString("content").length
        assertTrue(total <= 8000)
        assertEquals("system", bounded.getJSONObject(0).optString("role"))
        assertEquals("latest request", bounded.getJSONObject(bounded.length() - 1).optString("content"))
    }

    @Test fun emptyInputRemainsEmpty() {
        assertEquals(0, RequestPayloadPolicy.bound(JSONArray()).length())
    }

    @Test fun truncatesOversizedIndividualMessage() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "system"))
            .put(JSONObject().put("role", "user").put("content", "X".repeat(10000)))
        val bounded = RequestPayloadPolicy.bound(messages)
        assertTrue(bounded.getJSONObject(1).optString("content").length <= RequestPayloadPolicy.MAX_MESSAGE_CHARS)
        assertTrue(bounded.getJSONObject(1).optString("content").contains("trimmed"))
    }
}
