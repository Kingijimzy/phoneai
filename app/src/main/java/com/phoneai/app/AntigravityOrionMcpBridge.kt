package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loopback-only Streamable HTTP MCP bridge for Antigravity.
 *
 * It exposes only the exact Orion tool definitions selected for the current turn.
 * Every call is sent back into Agent.executeWithRecoveryGuard; this bridge never
 * performs Android actions itself and never bypasses Orion's permission checks.
 */
internal class AntigravityOrionMcpBridge(
    tools: List<ToolDefinition>,
    private val execute: (String, JSONObject) -> String
) : Closeable {
    private val allowedTools = tools.distinctBy { it.name }.associateBy { it.name }
    private val sessionId = UUID.randomUUID().toString()
    val serverName: String = "orion_phoneai_" + UUID.randomUUID().toString().replace("-", "").take(12)
    private val closed = AtomicBoolean(false)
    private val initialized = AtomicBoolean(false)
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val requests = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "orion-antigravity-mcp-request").apply { isDaemon = true }
    }
    private val acceptThread = Thread({
        while (!closed.get()) {
            try {
                val socket = server.accept()
                requests.execute { handle(socket) }
            } catch (_: Exception) {
                if (!closed.get()) close()
            }
        }
    }, "orion-antigravity-mcp-accept").apply { isDaemon = true }

    val serverUrl: String = "http://127.0.0.1:${server.localPort}/mcp"

    init {
        require(allowedTools.isNotEmpty()) { "At least one registered Orion tool is required for the MCP bridge." }
        acceptThread.start()
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String
    )

    private fun handle(socket: Socket) {
        socket.use { client ->
            runCatching {
                client.soTimeout = 10_000
                val request = readRequest(client) ?: return@runCatching
                if (request.path.substringBefore('?') != "/mcp") {
                    respond(client, 404, null)
                    return@runCatching
                }
                if (request.method != "POST") {
                    respond(client, 405, null, mapOf("Allow" to "POST"))
                    return@runCatching
                }
                val suppliedSession = request.headers["mcp-session-id"].orEmpty()
                if (initialized.get() && suppliedSession != sessionId) {
                    respond(client, 404, null)
                    return@runCatching
                }
                val message = runCatching { JSONObject(request.body) }.getOrElse {
                    respond(client, 400, rpcError(JSONObject.NULL, -32700, "Invalid JSON"))
                    return@runCatching
                }
                val id = message.opt("id")
                val method = message.optString("method")
                val params = message.optJSONObject("params") ?: JSONObject()
                if (id == null || id == JSONObject.NULL) {
                    if (method == "notifications/initialized" || method.startsWith("notifications/")) {
                        respond(client, 202, null)
                    } else {
                        respond(client, 202, null)
                    }
                    return@runCatching
                }
                if (method != "initialize" && !initialized.get()) {
                    respond(client, 200, rpcError(id, -32002, "MCP session is not initialized"))
                    return@runCatching
                }
                val result = when (method) {
                    "initialize" -> {
                        initialized.set(true)
                        JSONObject()
                            .put("protocolVersion", params.optString("protocolVersion").ifBlank { "2025-03-26" })
                            .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                            .put("serverInfo", JSONObject().put("name", "orion-phoneai").put("version", "1.0.0"))
                    }
                    "ping" -> JSONObject()
                    "tools/list" -> JSONObject().put("tools", toolList())
                    "tools/call" -> callTool(params)
                    else -> {
                        respond(client, 200, rpcError(id, -32601, "Method not found: $method"))
                        return@runCatching
                    }
                }
                val envelope = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
                val headers = if (method == "initialize") mapOf("Mcp-Session-Id" to sessionId) else emptyMap()
                respond(client, 200, envelope, headers)
            }.onFailure {
                runCatching { respond(client, 500, null) }
            }
        }
    }

    private fun toolList(): JSONArray = JSONArray().apply {
        allowedTools.values.forEach { tool ->
            put(
                JSONObject()
                    .put("name", tool.name)
                    .put("description", tool.promptLine.take(4_000))
                    .put("inputSchema", JSONObject().put("type", "object").put("additionalProperties", true))
            )
        }
    }

    private fun callTool(params: JSONObject): JSONObject {
        val name = params.optString("name")
        val tool = allowedTools[name]
            ?: return toolResult("error: tool '$name' is not registered for this Orion turn.", true)
        val args = params.optJSONObject("arguments")
            ?: return toolResult("error: invalid JSON arguments for '$name'.", true)
        val result = runCatching { execute(tool.name, JSONObject(args.toString())) }
            .getOrElse { "error: " + (it.message ?: it.javaClass.simpleName) }
        return toolResult(result, result.startsWith("error:", ignoreCase = true))
    }

    private fun toolResult(text: String, isError: Boolean): JSONObject =
        JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text.take(OrionNativeToolDispatcher.MAX_RESULT_CHARS))))
            .put("isError", isError)

    private fun rpcError(id: Any?, code: Int, message: String): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("error", JSONObject().put("code", code).put("message", message))

    private fun readRequest(socket: Socket): HttpRequest? {
        val input = socket.getInputStream()
        val headerBytes = ByteArrayOutputStream()
        var matched = 0
        val marker = byteArrayOf(13, 10, 13, 10)
        while (headerBytes.size() < 16_384) {
            val next = input.read()
            if (next < 0) return null
            headerBytes.write(next)
            matched = when {
                next == marker[matched].toInt() -> matched + 1
                next == marker[0].toInt() -> 1
                else -> 0
            }
            if (matched == marker.size) break
        }
        if (matched != marker.size) return null
        val headerText = String(headerBytes.toByteArray(), StandardCharsets.US_ASCII)
        val lines = headerText.split("\r\n")
        val requestLine = lines.firstOrNull()?.split(' ') ?: return null
        if (requestLine.size < 2) return null
        val headers = lines.drop(1).mapNotNull { line ->
            val split = line.indexOf(':')
            if (split <= 0) null else line.substring(0, split).trim().lowercase() to line.substring(split + 1).trim()
        }.toMap()
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length !in 0..1_048_576) return null
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(body, offset, length - offset)
            if (count < 0) return null
            offset += count
        }
        return HttpRequest(
            requestLine[0].uppercase(),
            requestLine[1],
            headers,
            String(body, StandardCharsets.UTF_8)
        )
    }

    private fun respond(
        socket: Socket,
        status: Int,
        body: JSONObject?,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        val payload = body?.toString()?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
        val phrase = when (status) {
            200 -> "OK"
            202 -> "Accepted"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Internal Server Error"
        }
        val output = socket.getOutputStream()
        val headers = buildString {
            append("HTTP/1.1 $status $phrase\r\n")
            append("Content-Length: ${payload.size}\r\n")
            append("Connection: close\r\n")
            if (body != null) append("Content-Type: application/json\r\n")
            extraHeaders.forEach { (key, value) -> append("$key: $value\r\n") }
            append("\r\n")
        }
        output.write(headers.toByteArray(StandardCharsets.US_ASCII))
        if (payload.isNotEmpty()) output.write(payload)
        output.flush()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        requests.shutdownNow()
    }
}
