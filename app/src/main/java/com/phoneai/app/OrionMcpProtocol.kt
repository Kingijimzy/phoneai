package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/** JSON-RPC 2.0 message contracts for the MCP host adapter. Transport is deliberately separate. */
internal object OrionMcpProtocol {
    const val VERSION = "2025-03-26"
    private val ids = AtomicLong(0L)

    fun initialize(clientName: String = "Orion PhoneAI", clientVersion: String = "1.0"): JSONObject =
        request("initialize", JSONObject()
            .put("protocolVersion", VERSION)
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", clientName).put("version", clientVersion)))

    fun initializedNotification(): JSONObject = notification("notifications/initialized", JSONObject())

    fun listTools(cursor: String? = null): JSONObject {
        val params = JSONObject()
        if (!cursor.isNullOrBlank()) params.put("cursor", cursor)
        return request("tools/list", params)
    }

    fun callTool(name: String, arguments: JSONObject): JSONObject {
        require(OrionMcpPolicy.validateToolName(name))
        return request("tools/call", JSONObject().put("name", name).put("arguments", arguments))
    }

    fun request(method: String, params: JSONObject): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", ids.incrementAndGet())
            .put("method", method).put("params", params)

    fun notification(method: String, params: JSONObject): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params)

    fun parseResponse(response: JSONObject, expectedId: Long? = null): JSONObject {
        require(response.optString("jsonrpc") == "2.0") { "MCP response is not JSON-RPC 2.0." }
        if (expectedId != null) require(response.optLong("id", -1L) == expectedId) { "MCP response ID mismatch." }
        val error = response.optJSONObject("error")
        if (error != null) throw IllegalStateException("MCP error ${error.optInt("code")}: ${error.optString("message")}")
        return response.optJSONObject("result") ?: JSONObject()
    }

    fun tools(result: JSONObject): JSONArray = result.optJSONArray("tools") ?: JSONArray()
}
