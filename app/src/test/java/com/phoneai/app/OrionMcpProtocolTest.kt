package com.phoneai.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrionMcpProtocolTest {
    @Test fun initializeAndToolCallsUseJsonRpcContracts() {
        val init = OrionMcpProtocol.initialize()
        assertEquals("2.0", init.optString("jsonrpc"))
        assertEquals("initialize", init.optString("method"))
        assertEquals(OrionMcpProtocol.VERSION, init.optJSONObject("params")?.optString("protocolVersion"))
        val list = OrionMcpProtocol.listTools()
        assertEquals("tools/list", list.optString("method"))
        val call = OrionMcpProtocol.callTool("calendar.read", JSONObject().put("range", "today"))
        assertEquals("tools/call", call.optString("method"))
    }

    @Test fun resourcesAndPromptsUseBoundedReadOnlyMcpContracts() {
        val resources = OrionMcpProtocol.listResources("next-page")
        assertEquals("resources/list", resources.optString("method"))
        assertEquals("next-page", resources.optJSONObject("params")?.optString("cursor"))

        val read = OrionMcpProtocol.readResource("orion://memory/approved")
        assertEquals("resources/read", read.optString("method"))
        assertEquals("orion://memory/approved", read.optJSONObject("params")?.optString("uri"))
        assertThrows(IllegalArgumentException::class.java) {
            OrionMcpProtocol.readResource("https://user:secret@example.com/private")
        }
        assertThrows(IllegalArgumentException::class.java) {
            OrionMcpProtocol.readResource("orion://memory/item#fragment")
        }

        val prompts = OrionMcpProtocol.listPrompts()
        assertEquals("prompts/list", prompts.optString("method"))
        val get = OrionMcpProtocol.getPrompt("daily-summary", JSONObject().put("date", "today"))
        assertEquals("prompts/get", get.optString("method"))
        assertEquals("daily-summary", get.optJSONObject("params")?.optString("name"))
        assertThrows(IllegalArgumentException::class.java) {
            OrionMcpProtocol.getPrompt("../arbitrary")
        }
        assertThrows(IllegalArgumentException::class.java) {
            OrionMcpProtocol.listPrompts("bad\\ncursor")
        }

        val resourceResult = JSONObject().put("contents", org.json.JSONArray().put(
            JSONObject().put("uri", "orion://memory/approved").put("text", "untrusted memory data")
        ))
        assertEquals(1, OrionMcpProtocol.resourceContents(resourceResult).length())
        assertEquals(0, OrionMcpProtocol.prompts(JSONObject()).length())
    }

    @Test fun responseParserRejectsMismatchedIdsAndSurfacesProtocolErrors() {
        val ok = JSONObject().put("jsonrpc", "2.0").put("id", 7).put("result", JSONObject().put("ok", true))
        assertTrue(OrionMcpProtocol.parseResponse(ok, 7).optBoolean("ok"))
        assertThrows(IllegalArgumentException::class.java) { OrionMcpProtocol.parseResponse(ok, 8) }
        val failed = JSONObject().put("jsonrpc", "2.0").put("id", 7)
            .put("error", JSONObject().put("code", -1).put("message", "denied"))
        assertThrows(IllegalStateException::class.java) { OrionMcpProtocol.parseResponse(failed) }
    }
    @Test fun streamableHttpParserSelectsOnlyMatchingSseResponseId() {
        val raw = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ignored\":true}}\n\n" +
            "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"accepted\":true}}\n\n"
        val parsed = McpConnector.parseSseResponse(raw, 2L)
        assertEquals(2L, parsed.optLong("id"))
        assertTrue(parsed.optJSONObject("result")?.optBoolean("accepted") == true)
    }

    @Test fun mcpReconnectPolicyNeverRetriesAmbiguousToolCalls() {
        assertTrue(McpReconnectPolicy.shouldRetry("tools/list", java.io.IOException("timeout")))
        assertFalse(McpReconnectPolicy.shouldRetry("tools/call", java.io.IOException("timeout")))
        assertFalse(McpReconnectPolicy.shouldRetry("tools/list", IllegalArgumentException("bad request")))
    }

}
