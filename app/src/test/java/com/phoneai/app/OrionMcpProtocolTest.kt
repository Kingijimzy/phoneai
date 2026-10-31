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

    @Test fun responseParserRejectsMismatchedIdsAndSurfacesProtocolErrors() {
        val ok = JSONObject().put("jsonrpc", "2.0").put("id", 7).put("result", JSONObject().put("ok", true))
        assertTrue(OrionMcpProtocol.parseResponse(ok, 7).optBoolean("ok"))
        assertThrows(IllegalArgumentException::class.java) { OrionMcpProtocol.parseResponse(ok, 8) }
        val failed = JSONObject().put("jsonrpc", "2.0").put("id", 7)
            .put("error", JSONObject().put("code", -1).put("message", "denied"))
        assertThrows(IllegalStateException::class.java) { OrionMcpProtocol.parseResponse(failed) }
    }
}
