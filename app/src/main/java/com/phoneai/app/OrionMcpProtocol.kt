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

    fun listTools(cursor: String? = null): JSONObject = paginatedRequest("tools/list", cursor)

    /** MCP resource discovery/read are read-only protocol operations. */
    fun listResources(cursor: String? = null): JSONObject = paginatedRequest("resources/list", cursor)

    fun readResource(uri: String): JSONObject {
        require(validResourceUri(uri)) { "Invalid MCP resource URI." }
        return request("resources/read", JSONObject().put("uri", uri))
    }

    /** Prompt discovery is read-only; prompt arguments are data, never executable instructions. */
    fun listPrompts(cursor: String? = null): JSONObject = paginatedRequest("prompts/list", cursor)

    fun getPrompt(name: String, arguments: JSONObject = JSONObject()): JSONObject {
        require(validatePromptName(name)) { "Invalid MCP prompt name." }
        require(arguments.toString().length <= 8_000) { "MCP prompt arguments exceed 8 KB." }
        return request("prompts/get", JSONObject().put("name", name).put("arguments", arguments))
    }

    fun resourceContents(result: JSONObject): JSONArray =
        result.optJSONArray("contents") ?: JSONArray()

    fun prompts(result: JSONObject): JSONArray =
        result.optJSONArray("prompts") ?: JSONArray()

    fun tools(result: JSONObject): JSONArray = result.optJSONArray("tools") ?: JSONArray()

    private fun paginatedRequest(method: String, cursor: String?): JSONObject {
        val params = JSONObject()
        if (!cursor.isNullOrBlank()) {
            require(cursor.length <= 2_000 && cursor.none { it.isISOControl() }) { "Invalid MCP pagination cursor." }
            params.put("cursor", cursor)
        }
        return request(method, params)
    }

    private fun validatePromptName(name: String): Boolean =
        name.length in 1..128 && name.matches(Regex("^[A-Za-z0-9][A-Za-z0-9_.:/-]*$"))

    private fun validResourceUri(uri: String): Boolean {
        if (uri.length !in 1..2_000 || uri.any { it.isISOControl() }) return false
        return runCatching {
            val parsed = java.net.URI(uri)
            parsed.scheme?.matches(Regex("^[A-Za-z][A-Za-z0-9+.-]{0,31}$")) == true &&
                parsed.rawUserInfo == null && parsed.rawFragment == null
        }.getOrDefault(false)
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

}
