package com.phoneai.app

import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Synchronous JSON-RPC client for the official Codex app-server. Call only from worker threads. */
internal class CodexAppServerClient(
    private val session: LinuxCommandSession,
    private val requestTimeoutMs: Long = 30_000L,
    private val onNotification: (JSONObject) -> Unit = {},
    private val onClose: () -> Unit = {}
) : Closeable {
    private val ids = AtomicInteger(1)
    private val events = LinkedBlockingQueue<JSONObject>()
    private val startupDiagnostics = LinkedBlockingQueue<String>()
    @Volatile private var initialized = false
    @Volatile private var activeToolExecutor: ((String, JSONObject) -> String)? = null
    @Volatile private var activeThreadId: String? = null
    @Volatile private var activeTurnId: String? = null

    fun initialize(): Boolean {
        val response = request(CodexAppServerProtocol.initialize(ids.getAndIncrement()))
        session.writeLine(JSONObject().put("method", "initialized").put("params", JSONObject()).toString())
        initialized = CodexAppServerProtocol.isInitializeResponse(response)
        return initialized
    }

    fun beginChatGptLogin(): CodexAppServerProtocol.DeviceLogin {
        check(initialized) { "Codex app-server has not been initialized" }
        val result = request(CodexAppServerProtocol.startChatGptDeviceLogin(ids.getAndIncrement()))
        return CodexAppServerProtocol.parseDeviceLogin(result)
            ?: error("Codex app-server did not return a valid HTTPS device-login URL and user code")
    }

    fun readAccount(): CodexAppServerProtocol.Account? {
        check(initialized) { "Codex app-server has not been initialized" }
        return CodexAppServerProtocol.parseAccount(request(CodexAppServerProtocol.readAccount(ids.getAndIncrement())))
    }

    fun availableModels(): List<String> {
        check(initialized) { "Codex app-server has not been initialized" }
        return CodexAppServerProtocol.parseModels(request(CodexAppServerProtocol.listModels(ids.getAndIncrement())))
    }

    fun startThread(model: String? = null, tools: List<ToolDefinition> = emptyList()): String {
        val result = request(CodexAppServerProtocol.startThread(ids.getAndIncrement(), model, tools))
        return result.optJSONObject("thread")?.optString("id")?.takeIf { it.isNotBlank() }
            ?: result.optString("threadId").takeIf { it.isNotBlank() }
            ?: error("Codex app-server did not return a thread id")
    }

    fun resumeThread(threadId: String): String {
        check(initialized) { "Codex app-server has not been initialized" }
        val result = request(CodexAppServerProtocol.resumeThread(ids.getAndIncrement(), threadId))
        return result.optJSONObject("thread")?.optString("id")?.takeIf { it.isNotBlank() }
            ?: error("Codex app-server did not resume thread '$threadId'")
    }

    fun compactThread(threadId: String) {
        check(initialized) { "Codex app-server has not been initialized" }
        request(CodexAppServerProtocol.compactThread(ids.getAndIncrement(), threadId))
    }

    fun startTurn(threadId: String, text: String, model: String? = null): JSONObject =
        request(CodexAppServerProtocol.startTurn(ids.getAndIncrement(), threadId, text, model))

    fun runTurnAndAwait(
        threadId: String,
        text: String,
        model: String? = null,
        timeoutMs: Long = 300_000L,
        onDelta: ((String) -> Unit)? = null,
        onToolCall: ((String, JSONObject) -> String)? = null
    ): String {
        activeToolExecutor = onToolCall
        activeThreadId = threadId
        val turnStart = startTurn(threadId, text, model)
        activeTurnId = turnStart.optJSONObject("turn")?.optString("id")?.takeIf { it.isNotBlank() }
        val answer = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastEvent = "none"
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) {
                // Interrupt the active turn but leave its durable thread intact. The next
                // request can resume it and inspect the interrupted terminal status.
                val turnId = activeTurnId
                if (!turnId.isNullOrBlank()) {
                    runCatching {
                        session.writeLine(
                            CodexAppServerProtocol.interruptTurn(
                                ids.getAndIncrement(), threadId, turnId
                            ).toJson().toString()
                        )
                    }
                }
                val diagnostics = session.stderrSnapshot().takeLast(8).joinToString("\\n").takeLast(1_200)
                error(
                    "Codex turn timed out before turn/completed (thread=$threadId, turn=${activeTurnId ?: "unknown"}, last event=$lastEvent)" +
                        if (diagnostics.isBlank()) "" else "; app-server stderr: $diagnostics"
                )
            }
            val queued = events.poll()
            val event = if (queued != null) queued else {
                val line = session.readLine(remaining.coerceAtMost(500L))
                if (line == null) {
                    if (!session.isAlive()) error("Codex app-server stopped before turn/completed")
                    session.readStderrLine(1L)
                    continue
                }
                val message = runCatching { JSONObject(line) }.getOrNull() ?: continue
                if (message.has("method") && message.has("id")) {
                    handleServerRequest(message)
                    continue
                }
                if (!CodexAppServerProtocol.isNotification(message)) continue
                message
            }
            if (!belongsToActiveTurn(event)) continue
            lastEvent = event.optString("method").ifBlank { "unknown notification" }
            when (event.optString("method")) {
                "item/agentMessage/delta" -> {
                    val delta = event.optJSONObject("params")?.optString("delta").orEmpty()
                    if (delta.isNotEmpty()) {
                        answer.append(delta)
                        onDelta?.invoke(delta)
                    }
                }
                "turn/completed" -> {
                    val turn = event.optJSONObject("params")?.optJSONObject("turn")
                    val status = turn?.optString("status").orEmpty()
                    val detail = turn?.optJSONObject("error")?.optString("message").orEmpty()
                    check(status == "completed") {
                        "Codex turn ended with status: ${status.ifBlank { "unknown" }}" +
                            if (detail.isBlank()) "" else " ($detail)"
                    }
                    return answer.toString()
                }
                "turn/failed", "turn/cancelled", "turn/canceled" -> {
                    val detail = event.optJSONObject("params")?.optJSONObject("error")?.optString("message").orEmpty()
                    error("Codex turn ended with ${event.optString("method")}" +
                        if (detail.isBlank()) "" else ": $detail")
                }
            }
        }
    }

    private fun belongsToActiveTurn(event: JSONObject): Boolean {
        val params = event.optJSONObject("params") ?: return true
        val eventThread = params.optString("threadId")
        if (eventThread.isNotBlank() && eventThread != activeThreadId) return false
        val turn = params.optJSONObject("turn")
        val eventTurn = params.optString("turnId").ifBlank { turn?.optString("id").orEmpty() }
        val expectedTurn = activeTurnId
        return eventTurn.isBlank() || expectedTurn.isNullOrBlank() || eventTurn == expectedTurn
    }

    private fun request(request: CodexAppServerProtocol.Request): JSONObject {
        check(session.isAlive()) { "Codex app-server process is not running" }
        session.writeLine(request.toJson().toString())
        val deadline = System.currentTimeMillis() + requestTimeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                val diagnostics = (startupDiagnostics.toList() + session.stderrSnapshot()).takeLast(20).joinToString("\n").takeLast(2_000)
                error("Timed out waiting for Codex app-server method " + request.method +
                    if (diagnostics.isBlank()) "" else ": " + diagnostics)
            }
            val line = session.readLine(remaining.coerceAtMost(500L))
            if (line == null) {
                if (!session.isAlive()) {
                    val diagnostics = session.stderrSnapshot().joinToString("\n").takeLast(2_000)
                    error("Codex app-server exited during " + request.method +
                        (session.exitCodeOrNull()?.let { " (exit=$it)" } ?: "") +
                        if (diagnostics.isBlank()) "" else ": " + diagnostics)
                }
                session.readStderrLine(1L)?.let { diagnostic ->
                    startupDiagnostics.offer(diagnostic.take(2_000))
                }
                continue
            }
            val message = runCatching { JSONObject(line) }.getOrNull() ?: continue
            if (message.has("method") && message.has("id")) {
                handleServerRequest(message)
                continue
            }
            if (CodexAppServerProtocol.isNotification(message)) {
                events.offer(message)
                onNotification(message)
                continue
            }
            if (message.optInt("id", Int.MIN_VALUE) != request.id) continue
            CodexAppServerProtocol.errorMessage(message)?.let { error("Codex app-server: " + it) }
            return message.optJSONObject("result") ?: JSONObject()
        }
    }

    private fun handleServerRequest(message: JSONObject) {
        val id = message.opt("id") ?: return
        val method = message.optString("method")
        val params = message.optJSONObject("params") ?: JSONObject()
        val response = when (method) {
            "item/tool/call" -> {
                val name = params.optString("tool").ifBlank { params.optString("name") }
                val rawArgs = params.opt("arguments")
                val args = when (rawArgs) {
                    is JSONObject -> rawArgs
                    is String -> runCatching { JSONObject(rawArgs) }.getOrNull()
                    else -> null
                }
                val result = runCatching {
                    require(CodexAppServerProtocol.toolCallBelongsToActiveTurn(
                        params,
                        activeThreadId,
                        activeTurnId
                    )) {
                        "Codex tool request is not bound to Orion's active thread and turn; no Orion action was dispatched."
                    }
                    require(name.isNotBlank()) { "Codex requested an unnamed Orion tool." }
                    require(args != null) { "Codex supplied invalid JSON arguments for Orion tool '$name'." }
                    val executor = activeToolExecutor
                        ?: error("Orion tool execution is unavailable for this Codex turn.")
                    executor(name, args)
                }
                val text = result.getOrElse { "error: " + (it.message ?: it.javaClass.simpleName) }
                CodexAppServerProtocol.dynamicToolResponse(
                    text,
                    result.isSuccess && !text.startsWith("error:", ignoreCase = true)
                )
            }
            // Never silently approve a built-in Codex command or file operation. The
            // auto-review reviewer is configured at thread start; if it still asks this
            // client directly, fail closed rather than bypass Orion's permission model.
            "item/commandExecution/requestApproval", "item/fileChange/requestApproval" ->
                JSONObject().put("decision", "decline")
            "item/permissions/requestApproval" ->
                JSONObject().put("permissions", org.json.JSONArray())
            else -> null
        }
        val envelope = if (response != null) {
            JSONObject().put("id", id).put("result", response)
        } else {
            JSONObject().put("id", id).put(
                "error",
                JSONObject().put("code", -32601).put("message", "Unsupported Codex app-server request: $method")
            )
        }
        session.writeLine(envelope.toString())
    }

    override fun close() {
        activeToolExecutor = null
        activeThreadId = null
        activeTurnId = null
        runCatching { session.close() }
        onClose()
    }
}
