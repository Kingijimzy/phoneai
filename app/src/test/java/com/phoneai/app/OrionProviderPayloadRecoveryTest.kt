package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrionProviderPayloadRecoveryTest {
    @Test fun compactionPreservesSystemAndLatestUserRequest() {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "Keep tool instructions."))
        repeat(12) { index ->
            messages.put(JSONObject().put("role", if (index % 2 == 0) "user" else "assistant")
                .put("content", "Earlier conversation $index " + "x".repeat(300)))
        }
        messages.put(JSONObject().put("role", "user").put("content", "Write scraper.py and test it."))
        val compact = OrionProviderPayloadRecovery.compact(messages, 1)
        assertEquals("Keep tool instructions.", compact.getJSONObject(0).getString("content"))
        assertEquals("Write scraper.py and test it.", compact.getJSONObject(compact.length() - 1).getString("content"))
        assertTrue(compact.toString().length < messages.toString().length)
    }

    @Test fun latestUserRequestSurvivesWhenManyToolTurnsFollowIt() {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "system"))
            .put(JSONObject().put("role", "user").put("content", "Do not lose this request"))
        repeat(14) { index ->
            messages.put(JSONObject().put("role", if (index % 2 == 0) "assistant" else "tool")
                .put("content", "large old turn $index " + "z".repeat(500)))
        }
        val compact = OrionProviderPayloadRecovery.compact(messages, 2)
        assertTrue((0 until compact.length()).any {
            compact.optJSONObject(it)?.optString("content") == "Do not lose this request"
        })
        assertTrue(compact.length() <= 6)
    }

    @Test fun compactionNeverTruncatesLatestUserContent() {
        val current = "Do this exact task: " + "keep this ".repeat(200)
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "system"))
            .put(JSONObject().put("role", "user").put("content", current))
        val compact = OrionProviderPayloadRecovery.compact(messages, 2)
        assertEquals(current, compact.getJSONObject(1).getString("content"))
    }

    @Test fun toolResultsAreShortenedWithoutDroppingCallMetadata() {
        val output = "tool output ".repeat(1000)
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "system"))
            .put(JSONObject().put("role", "user").put("content", "run"))
            .put(JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(JSONObject().put("id", "call-1"))))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "call-1").put("content", output))
        val compact = OrionProviderPayloadRecovery.compact(messages, 1)
        val tool = compact.getJSONObject(compact.length() - 1)
        assertEquals("call-1", tool.getString("tool_call_id"))
        assertTrue(tool.getString("content").length < output.length)
        assertTrue(tool.getString("content").contains("older tool output shortened"))
    }
}
