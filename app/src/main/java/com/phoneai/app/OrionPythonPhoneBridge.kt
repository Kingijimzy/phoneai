package com.phoneai.app

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/** Per-execution authenticated loopback RPC; only explicitly registered phone methods are exposed. */
internal class OrionPythonPhoneBridge(private val api: OrionPythonPhoneApi) : Closeable {
    private val token = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val running = AtomicBoolean(true)
    @Volatile private var activeSocket: Socket? = null
    private val worker = Thread({ serve() }, "OrionPythonPhoneBridge").apply { isDaemon = true; start() }

    fun environment(): Map<String, String> = mapOf(
        "ORION_PHONE_API_PORT" to server.localPort.toString(),
        "ORION_PHONE_API_TOKEN" to Base64.encodeToString(token, Base64.URL_SAFE or Base64.NO_WRAP)
    )

    override fun close() {
        if (running.compareAndSet(true, false)) {
            runCatching { server.close() }
            runCatching { activeSocket?.close() }
            worker.interrupt()
            runCatching { worker.join(300) }
        }
        token.fill(0)
    }

    private fun serve() {
        while (running.get()) {
            try {
                val socket = server.accept()
                activeSocket = socket
                try { socket.use { it.soTimeout = 40_000; handle(it) } }
                finally { activeSocket = null }
            } catch (_: Exception) { if (!running.get()) return }
        }
    }

    private fun handle(socket: Socket) {
        val input = socket.getInputStream()
        val requestLine = readLine(input, 4096) ?: return
        val parts = requestLine.split(' ')
        if (parts.size != 3 || parts[0] != "POST" || parts[1] != "/call" || parts[2] != "HTTP/1.1") {
            respond(socket, 404, JSONObject().put("ok", false).put("error", "not_found")); return
        }
        val headers = linkedMapOf<String, String>()
        var headerBytes = 0
        while (true) {
            val line = readLine(input, 8192) ?: return
            headerBytes += line.length
            if (headerBytes > 8192) { respond(socket, 431, JSONObject().put("ok", false).put("error", "headers_too_large")); return }
            if (line.isEmpty()) break
            val split = line.indexOf(':')
            if (split <= 0) { respond(socket, 400, JSONObject().put("ok", false).put("error", "invalid_header")); return }
            val key = line.substring(0, split).trim().lowercase()
            if (headers.containsKey(key)) { respond(socket, 400, JSONObject().put("ok", false).put("error", "duplicate_header")); return }
            headers[key] = line.substring(split + 1).trim()
        }
        if (headers.containsKey("transfer-encoding") ||
            headers["content-type"]?.substringBefore(';')?.trim()?.lowercase() != "application/json") {
            respond(socket, 400, JSONObject().put("ok", false).put("error", "unsupported_request_encoding")); return
        }
        val supplied = headers["authorization"]?.removePrefix("Bearer ")?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val expected = Base64.encodeToString(token, Base64.URL_SAFE or Base64.NO_WRAP).toByteArray(Charsets.UTF_8)
        if (!MessageDigest.isEqual(expected, supplied)) { respond(socket, 401, JSONObject().put("ok", false).put("error", "unauthorized")); return }
        val length = headers["content-length"]?.toIntOrNull()
        if (length == null || length !in 2..MAX_REQUEST_BYTES) {
            respond(socket, 413, JSONObject().put("ok", false).put("error", "invalid_content_length")); return
        }
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(bytes, offset, length - offset)
            if (count < 0) { respond(socket, 400, JSONObject().put("ok", false).put("error", "truncated_body")); return }
            offset += count
        }
        val result = try {
            val payload = JSONObject(bytes.toString(Charsets.UTF_8))
            val value = dispatch(payload.optString("method"), payload.optJSONArray("args") ?: JSONArray())
            JSONObject().put("ok", true).put("result", value)
        } catch (failure: IllegalArgumentException) {
            JSONObject().put("ok", false).put("error", failure.message ?: "invalid_arguments")
        } catch (failure: Exception) {
            JSONObject().put("ok", false).put("error", "phone_api_failed:" + failure.javaClass.simpleName)
        }
        respond(socket, 200, result)
    }

    private fun dispatch(method: String, args: JSONArray): String {
        require(method in registeredMethods) { "phone_method_not_allowed" }
        fun count(expected: Int) { require(args.length() == expected) { "invalid_argument_count" } }
        fun text(index: Int): String {
            val value = args.opt(index)
            require(value is String) { "argument_" + (index + 1) + "_must_be_text" }
            return value as String
        }
        fun number(index: Int): Double {
            val value = args.opt(index)
            require(value is Number) { "argument_" + (index + 1) + "_must_be_number" }
            return (value as Number).toDouble()
        }
        return when (method) {
            "battery" -> { count(0); api.battery() }
            "location" -> { count(0); api.location() }
            "notifications" -> { count(0); api.notifications() }
            "contacts" -> { count(1); api.contacts(text(0)) }
            "accessibility_open_settings" -> { count(0); api.accessibility_open_settings() }
            "accessibility_status" -> { count(0); api.accessibility_status() }
            "accessibility_screen" -> { count(0); api.accessibility_screen() }
            "ui_tap_text" -> { count(1); api.ui_tap_text(text(0)) }
            "ui_tap" -> { count(2); api.ui_tap(number(0), number(1)) }
            "ui_swipe" -> { count(4); api.ui_swipe(number(0), number(1), number(2), number(3)) }
            "fs_list" -> { count(0); api.fs_list() }
            "fs_read" -> { count(1); api.fs_read(text(0)) }
            "fs_write" -> { count(2); api.fs_write(text(0), text(1)) }
            "shell" -> { count(1); api.shell(text(0)) }
            "ws_connect" -> { count(1); api.ws_connect(text(0)) }
            "ws_connect_with_headers" -> { count(2); api.ws_connect_with_headers(text(0), text(1)) }
            "ws_send" -> { count(2); api.ws_send(text(0), text(1)) }
            "ws_send_binary" -> { count(2); api.ws_send_binary(text(0), text(1)) }
            "ws_receive" -> { count(2); require(args.opt(1) is Number) { "argument_2_must_be_number" }; api.ws_receive(text(0), args.optInt(1)) }
            "ws_close" -> { count(1); api.ws_close(text(0)) }
            "http_get" -> { count(1); api.http_get(text(0)) }
            "tool" -> {
                count(2)
                val raw = args.opt(1)
                val toolArgs = when (raw) {
                    is JSONObject -> raw
                    is String -> JSONObject(raw)
                    else -> throw IllegalArgumentException("argument_2_must_be_object")
                }
                api.tool(text(0), toolArgs)
            }
            "http_post" -> { count(2); api.http_post(text(0), text(1)) }
            else -> throw IllegalArgumentException("phone_method_not_allowed")
        }
    }

    private fun readLine(input: InputStream, maxBytes: Int): String? {
        val output = ByteArrayOutputStream()
        var previous = -1
        while (true) {
            val value = input.read()
            if (value < 0) return if (output.size() == 0) null else String(output.toByteArray(), Charsets.US_ASCII)
            if (previous == 13 && value == 10) {
                val bytes = output.toByteArray()
                val length = if (bytes.lastOrNull()?.toInt() == 13) bytes.size - 1 else bytes.size
                return String(bytes.copyOf(length), Charsets.US_ASCII)
            }
            output.write(value)
            if (output.size() > maxBytes) throw IllegalArgumentException("header_line_too_large")
            previous = value
        }
    }

    private fun respond(socket: Socket, status: Int, body: JSONObject) {
        val raw = body.toString().toByteArray(Charsets.UTF_8)
        val responseStatus = if (raw.size > MAX_RESPONSE_BYTES) 413 else status
        val bytes = if (raw.size > MAX_RESPONSE_BYTES)
            JSONObject().put("ok", false).put("error", "phone_api_response_too_large").toString().toByteArray(Charsets.UTF_8)
        else raw
        val reason = when (responseStatus) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"
            404 -> "Not Found"; 413 -> "Payload Too Large"; 431 -> "Request Header Fields Too Large"
            else -> "Error"
        }
        val header = "HTTP/1.1 $responseStatus $reason\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n"
        socket.getOutputStream().apply {
            write(header.toByteArray(Charsets.US_ASCII))
            write(bytes)
            flush()
        }
    }

    companion object {
        private const val MAX_REQUEST_BYTES = 8 * 1024 * 1024
        private const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
        internal val registeredMethods: Set<String> = setOf(
            "battery", "location", "notifications", "contacts",
            "accessibility_open_settings", "accessibility_status", "accessibility_screen",
            "ui_tap_text", "ui_tap", "ui_swipe", "fs_list", "fs_read", "fs_write", "shell",
            "ws_connect", "ws_connect_with_headers", "ws_send", "ws_send_binary", "ws_receive", "ws_close",
            "http_get", "http_post", "tool"
        )

        internal fun bootstrapSource(full: Boolean = false): String {
            val pythonMethods = registeredMethods.joinToString(", ") { "\"$it\"" }
            return """
import os, json, sys, urllib.request, urllib.error
class _OrionPhone:
    _allowed = {$pythonMethods}
    def __getattr__(self, name):
        if name not in self._allowed:
            raise AttributeError("phone API method is not registered: " + name)
        def call(*args):
            payload = json.dumps({"method": name, "args": args}, separators=(",", ":")).encode("utf-8")
            request = urllib.request.Request(
                "http://127.0.0.1:" + os.environ["ORION_PHONE_API_PORT"] + "/call",
                data=payload,
                headers={
                    "Authorization": "Bearer " + os.environ["ORION_PHONE_API_TOKEN"],
                    "Content-Type": "application/json"
                },
                method="POST"
            )
            try:
                with urllib.request.urlopen(request, timeout=(280 if name == "tool" else 40)) as response:
                    result = json.loads(response.read().decode("utf-8"))
            except urllib.error.HTTPError as error:
                result = json.loads(error.read().decode("utf-8"))
            if not result.get("ok"):
                raise RuntimeError(result.get("error", "phone API request failed"))
            return result.get("result")
        return call
phone = _OrionPhone()
# Isolated mode excludes the current directory; expose only Orion's private project workspace for sibling imports.
sys.path.insert(0, "/workspace")
def _orion_audit(event, args):
    if event == "socket.getaddrinfo":
        host = args[0] if args else None
        if host not in ("127.0.0.1", "::1", "localhost"):
            raise PermissionError("Use the owner-approved phone network facade.")
    elif event == "socket.connect":
        address = args[1] if len(args) > 1 else None
        host = address[0] if isinstance(address, tuple) and address else None
        port = address[1] if isinstance(address, tuple) and len(address) > 1 else None
        if host not in ("127.0.0.1", "::1") or int(port or -1) != int(os.environ["ORION_PHONE_API_PORT"]):
            raise PermissionError("Use phone.http_get/http_post/phone.ws_* for network access.")
    elif event in ("socket.bind", "subprocess.Popen", "os.system", "os.exec", "os.spawn", "os.posix_spawn", "os.posix_spawnp", "os.fork", "ctypes.dlopen"):
        raise PermissionError("This Python runtime does not expose raw sockets, subprocesses or native loading.")
${if (full) "# FULL profile (owner opt-in): raw sockets, subprocesses and native loading are allowed inside the guest." else "sys.addaudithook(_orion_audit)"}
with open("/workspace/orion_main.py", "rb") as source_file:
    exec(compile(source_file.read(), "/workspace/orion_main.py", "exec"),
         {"__name__": "__main__", "phone": phone})
"""
        }

        fun bootstrapCommand(full: Boolean = false): String {
            val encoded = Base64.encodeToString(
                bootstrapSource(full).toByteArray(Charsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP
            )
            return "import base64;exec(base64.urlsafe_b64decode(\"$encoded\"))"
        }
    }
}
