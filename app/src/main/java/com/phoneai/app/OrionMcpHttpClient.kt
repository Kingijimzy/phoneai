package com.phoneai.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/**
 * Minimal MCP Streamable HTTP client. Run only from a worker thread.
 * Tool results must still be checked against OrionMcpPolicy and dispatched through Orion's
 * existing approval/capability broker; this transport never executes Android actions itself.
 */
internal class OrionMcpHttpClient(
    endpoint: String,
    private val bearerToken: String? = null,
    private val timeoutMs: Int = 20_000
) {
    private val endpoint = endpoint.trim()
    @Volatile private var sessionId: String? = null
    @Volatile private var protocolVersion = OrionMcpProtocol.VERSION

    init {
        require(isAllowedEndpoint(this.endpoint)) { "MCP endpoint must use HTTPS or loopback HTTP." }
    }

    fun initialize(clientName: String = "Orion PhoneAI", clientVersion: String = "1.0"): JSONObject {
        val request = OrionMcpProtocol.initialize(clientName, clientVersion)
        val result = sendRequest(request)
        val negotiated = result.optString("protocolVersion")
        require(negotiated.isNotBlank()) { "MCP server did not negotiate a protocol version." }
        protocolVersion = negotiated
        sendNotification(OrionMcpProtocol.initializedNotification())
        return result
    }

    fun listTools(cursor: String? = null): JSONObject = sendRequest(OrionMcpProtocol.listTools(cursor))

    fun callTool(name: String, arguments: JSONObject): JSONObject {
        require(OrionMcpPolicy.validateToolName(name)) { "Invalid MCP tool name." }
        return sendRequest(OrionMcpProtocol.callTool(name, arguments))
    }

    fun closeSession() {
        val id = sessionId ?: return
        val connection = open("DELETE")
        try {
            connection.setRequestProperty("MCP-Session-Id", id)
            connection.responseCode
        } finally {
            connection.disconnect()
            sessionId = null
        }
    }

    private fun sendRequest(payload: JSONObject): JSONObject {
        val expectedId = payload.optLong("id", -1L)
        require(expectedId > 0L) { "MCP request must include a JSON-RPC id." }
        val response = exchange(payload, true)
        return OrionMcpProtocol.parseResponse(response, expectedId)
    }

    private fun sendNotification(payload: JSONObject) {
        exchange(payload, false)
    }

    private fun exchange(payload: JSONObject, expectsResponse: Boolean): JSONObject {
        val connection = open("POST")
        try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json, text/event-stream")
            connection.setRequestProperty("MCP-Protocol-Version", protocolVersion)
            sessionId?.let { connection.setRequestProperty("MCP-Session-Id", it) }
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            connection.getHeaderField("MCP-Session-Id")?.takeIf { it.isNotBlank() }?.let { sessionId = it }
            if (status !in 200..299) {
                val detail = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
                throw IllegalStateException("MCP HTTP $status: ${detail.take(240)}")
            }
            if (!expectsResponse || status == HttpURLConnection.HTTP_ACCEPTED) return JSONObject()
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            if (body.isBlank()) throw IllegalStateException("MCP server returned an empty response.")
            val contentType = connection.contentType.orEmpty()
            val json = if (contentType.contains("text/event-stream", ignoreCase = true)) {
                parseSseResponse(body, payload.optLong("id", -1L))
            } else JSONObject(body)
            return json
        } finally {
            connection.disconnect()
        }
    }

    private fun parseSseResponse(body: String, expectedId: Long): JSONObject {
        val events = body.split(Regex("\\r?\\n\\r?\\n"))
        for (event in events) {
            val data = event.lineSequence()
                .filter { it.startsWith("data:") }
                .map { it.removePrefix("data:").trim() }
                .joinToString("\\n")
            if (data.isBlank() || data == "[DONE]") continue
            val candidate = runCatching { JSONObject(data) }.getOrNull() ?: continue
            if (candidate.optLong("id", Long.MIN_VALUE) == expectedId) return candidate
        }
        throw IllegalStateException("MCP SSE stream ended without the matching JSON-RPC response.")
    }

    private fun open(method: String): HttpURLConnection =
        (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs.coerceIn(2_000, 60_000)
            readTimeout = timeoutMs.coerceIn(2_000, 60_000)
            setRequestProperty("Accept", "application/json, text/event-stream")
            bearerToken?.takeIf { it.isNotBlank() }?.let {
                setRequestProperty("Authorization", "Bearer $it")
            }
        }

    companion object {
        private val unusedIds = AtomicLong(0L)

        fun isAllowedEndpoint(value: String): Boolean {
            val uri = runCatching { URI(value) }.getOrNull() ?: return false
            val scheme = uri.scheme?.lowercase() ?: return false
            val host = uri.host?.lowercase() ?: return false
            if (uri.userInfo != null || uri.fragment != null) return false
            return scheme == "https" ||
                (scheme == "http" && host in setOf("localhost", "127.0.0.1", "::1"))
        }
    }
}
