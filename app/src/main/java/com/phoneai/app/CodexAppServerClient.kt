package com.phoneai.app

import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger

/** Synchronous JSON-RPC client for the official Codex app-server. Call only from worker threads. */
internal class CodexAppServerClient(
    private val session: LinuxCommandSession,
    private val requestTimeoutMs: Long = 30_000L,
    private val onNotification: (JSONObject) -> Unit = {}
) : Closeable {
    private val ids = AtomicInteger(1)
    @Volatile private var initialized = false

    fun initialize(): Boolean {
        val response = request(CodexAppServerProtocol.initialize(ids.getAndIncrement()))
        session.writeLine(JSONObject().put("jsonrpc", "2.0").put("method", "initialized").put("params", JSONObject()).toString())
        initialized = response.optJSONObject("serverInfo") != null || response.has("capabilities")
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

    fun startThread(model: String? = null): String {
        val result = request(CodexAppServerProtocol.startThread(ids.getAndIncrement(), model))
        return result.optJSONObject("thread")?.optString("id")?.takeIf { it.isNotBlank() }
            ?: result.optString("threadId").takeIf { it.isNotBlank() }
            ?: error("Codex app-server did not return a thread id")
    }

    fun startTurn(threadId: String, text: String, model: String? = null): JSONObject =
        request(CodexAppServerProtocol.startTurn(ids.getAndIncrement(), threadId, text, model))

    private fun request(request: CodexAppServerProtocol.Request): JSONObject {
        check(session.isAlive()) { "Codex app-server process is not running" }
        session.writeLine(request.toJson().toString())
        val deadline = System.currentTimeMillis() + requestTimeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) error("Timed out waiting for Codex app-server method " + request.method)
            val line = session.readLine(remaining)
                ?: error("Codex app-server stopped or timed out during " + request.method)
            val message = runCatching { JSONObject(line) }.getOrNull() ?: continue
            if (CodexAppServerProtocol.isNotification(message)) {
                onNotification(message)
                continue
            }
            if (message.optInt("id", Int.MIN_VALUE) != request.id) continue
            CodexAppServerProtocol.errorMessage(message)?.let { error("Codex app-server: " + it) }
            return message.optJSONObject("result") ?: JSONObject()
        }
    }

    override fun close() = session.close()
}
