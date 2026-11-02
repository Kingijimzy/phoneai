package com.phoneai.app

import android.content.Context
import android.util.Base64
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.net.Proxy
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Process-scoped, bounded WebSocket sessions for direct Python runs.
 * Sessions survive individual python_exec calls but intentionally do not survive process death.
 */
internal object OrionPythonWebSocketTransport {
    private const val MAX_SESSIONS = 3
    private const val MAX_FRAME_BYTES = 64 * 1024
    private const val MAX_QUEUED_EVENTS = 16
    private data class Session(
        val id: String,
        val url: String,
        val host: String,
        @Volatile var allowWrites: Boolean,
        val events: ArrayBlockingQueue<String> = ArrayBlockingQueue(MAX_QUEUED_EVENTS),
        @Volatile var socket: WebSocket? = null,
        @Volatile var client: OkHttpClient? = null,
        @Volatile var opened: Boolean = false,
        @Volatile var closed: Boolean = false,
        @Volatile var failure: String = ""
    ) {
        fun offer(event: JSONObject) {
            // JSON escaping/base64 can expand a valid 64 KiB frame. Never truncate serialized
            // JSON (which would make it unparsable); the bounded queue limits memory instead.
            val value = event.toString()
            if (!events.offer(value)) {
                events.poll()
                events.offer(value)
            }
        }
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    @Volatile private var appContext: Context? = null

    fun connect(context: Context, url: String, headersJson: String = "{}"): String {
        val headers = parseHeaders(headersJson) ?: return error("WebSocket headers are invalid or contain unsupported names")
        sessions.values.filter { it.closed && it.events.isEmpty() }.forEach { stale ->
            if (sessions.remove(stale.id, stale)) dispose(stale)
        }
        appContext = context.applicationContext
        val endpoint = OrionPythonNetworkPolicy.validateWebSocket(context, url)
            ?: return error("WebSocket blocked: grant NETWORK and explicitly allow this exact host; ws:// also needs local HTTP permission")
        if (sessions.size >= MAX_SESSIONS) return error("at most $MAX_SESSIONS WebSocket sessions may be open")
        if (!OrionSocketForegroundService.start(context)) return error("Android did not allow Orion to start its visible live-connection foreground service; keep Orion open and retry")
        val id = UUID.randomUUID().toString()
        val session = Session(id, endpoint.uri.toString(), endpoint.host, endpoint.allowWrites)
        sessions[id] = session
        val ready = CountDownLatch(1)
        val client = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    if (!hostname.equals(endpoint.host, ignoreCase = true)) {
                        throw UnknownHostException("WebSocket redirect or secondary host is blocked")
                    }
                    return endpoint.addresses
                }
            })
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
        session.client = client
        val requestBuilder = Request.Builder().url(endpoint.uri.toString()).header("User-Agent", "Orion-PhoneAI/1")
        headers.forEach { (name, value) -> requestBuilder.header(name, value) }
        val request = requestBuilder.build()
        try {
            session.socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    session.opened = true
                    session.offer(JSONObject().put("type", "open").put("status", response.code))
                    ready.countDown()
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.toByteArray(Charsets.UTF_8).size <= MAX_FRAME_BYTES) {
                        session.offer(JSONObject().put("type", "text").put("data", text))
                    } else session.offer(JSONObject().put("type", "error").put("error", "incoming text frame exceeded 64 KiB"))
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (bytes.size <= MAX_FRAME_BYTES) {
                        session.offer(JSONObject().put("type", "binary_base64").put("data", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)))
                    } else session.offer(JSONObject().put("type", "error").put("error", "incoming binary frame exceeded 64 KiB"))
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    session.offer(JSONObject().put("type", "closing").put("code", code).put("reason", reason.take(160)))
                    webSocket.close(code, null)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    session.closed = true
                    session.offer(JSONObject().put("type", "closed").put("code", code).put("reason", reason.take(160)))
                    dispose(session)
                    stopServiceIfIdle()
                    ready.countDown()
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    session.failure = t.javaClass.simpleName
                    session.closed = true
                    session.offer(JSONObject().put("type", "error").put("error", t.javaClass.simpleName))
                    dispose(session)
                    stopServiceIfIdle()
                    ready.countDown()
                }
            })
            if (!ready.await(6, TimeUnit.SECONDS) || !session.opened) {
                session.socket?.cancel()
                sessions.remove(id)
                stopServiceIfIdle()
                return error("WebSocket connection did not open: ${session.failure.ifBlank { "timeout or handshake rejected" }}")
            }
            return JSONObject().put("session_id", id).put("host", endpoint.host)
                .put("transport", endpoint.uri.scheme).put("status", "connected").toString()
        } catch (e: Exception) {
            session.socket?.cancel()
            sessions.remove(id)
            stopServiceIfIdle()
            return error("WebSocket connection failed: ${e.javaClass.simpleName}")
        }
    }

    fun send(
        context: Context,
        id: String,
        data: String,
        binaryBase64: Boolean = false,
        confirm: (String) -> Boolean = { false }
    ): String {
        val session = authorized(context, id) ?: return error("WebSocket session is missing, closed, or its network grant was revoked")
        if (!session.allowWrites && !confirm("Send data over the owner-approved WebSocket host ${session.host}?"))
            return error("owner approval is required to send WebSocket data to this host")
        if (data.toByteArray(Charsets.UTF_8).size > MAX_FRAME_BYTES * 2) return error("outgoing frame exceeds the 64 KiB limit")
        if (session.closed) return error("WebSocket session is closed")
        val socket = session.socket ?: return error("WebSocket is not connected")
        val sent = if (binaryBase64) {
            val bytes = runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull()
                ?: return error("binary frame must be valid base64")
            if (bytes.size > MAX_FRAME_BYTES) return error("binary frame exceeds 64 KiB")
            socket.send(ByteString.of(*bytes))
        } else {
            if (data.toByteArray(Charsets.UTF_8).size > MAX_FRAME_BYTES) return error("text frame exceeds 64 KiB")
            socket.send(data)
        }
        return if (sent) JSONObject().put("sent", true).put("session_id", id).toString()
            else error("WebSocket send queue is full or the connection is closed")
    }

    fun receive(context: Context, id: String, timeoutMs: Long): String {
        val session = authorized(context, id) ?: return error("WebSocket session is missing, closed, or its network grant was revoked")
        val event = session.events.poll(timeoutMs.coerceIn(0L, 30_000L), TimeUnit.MILLISECONDS)
        if (event == null && session.closed) {
            sessions.remove(id)
            dispose(session)
            stopServiceIfIdle()
            return JSONObject().put("type", "closed").put("session_id", id).toString()
        }
        return event ?: JSONObject().put("type", "timeout").put("session_id", id).toString()
    }

    fun close(id: String): String {
        val session = sessions.remove(id) ?: return error("WebSocket session not found")
        session.closed = true
        val socket = session.socket
        if (socket != null) socket.close(1000, "Orion session closed") else dispose(session)
        stopServiceIfIdle()
        return JSONObject().put("closed", true).put("session_id", id).toString()
    }

    private fun parseHeaders(source: String): Map<String, String>? {
        if (source.length > 4096) return null
        val allowed = setOf("authorization", "x-goog-api-key", "x-api-key", "sec-websocket-protocol")
        return try {
            val json = JSONObject(source.ifBlank { "{}" })
            if (json.length() > 8) return null
            val result = linkedMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.optString(key, "")
                if (key.lowercase() !in allowed || value.isBlank() || value.length > 2048 ||
                    value.contains('\r') || value.contains('\n')) return null
                result[key] = value
            }
            result
        } catch (_: Exception) { null }
    }

    private fun authorized(context: Context, id: String): Session? {
        val session = sessions[id] ?: return null
        val endpoint = OrionPythonNetworkPolicy.validateWebSocket(context, session.url)
        if (endpoint == null) {
            close(id)
            return null
        }
        session.allowWrites = endpoint.allowWrites
        return session
    }

    private fun stopServiceIfIdle() {
        if (sessions.values.none { !it.closed }) appContext?.let { OrionSocketForegroundService.stop(it) }
    }

    private fun dispose(session: Session) {
        session.client?.let { client ->
            runCatching { client.dispatcher.executorService.shutdown() }
            runCatching { client.connectionPool.evictAll() }
        }
        session.client = null
    }

    private fun error(message: String) = JSONObject().put("error", message.take(240)).toString()
}