package com.phoneai.app

import android.content.Context
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import org.json.JSONArray
import org.json.JSONObject

/**
 * LAN / Tailscale transport for the mesh trust layer in OrionMesh. One JSON line in, one JSON line out, over TCP.
 * Every request is signed (OrionMesh) and checked against the pairing's scopes before anything runs.
 */
internal object OrionMeshLink {
    const val PORT = 47821
    private const val PREFS = "orion_mesh"

    data class Peer(val id: String, val name: String, val host: String, val secretHex: String, val scopes: Set<String>)

    fun encode(e: OrionMesh.Envelope): String = JSONObject().put("from", e.from).put("action", e.action).put("body", e.body)
        .put("nonce", e.nonce).put("ts", e.ts).put("sig", e.sig).toString()

    fun decode(line: String): OrionMesh.Envelope? = runCatching {
        JSONObject(line).let { OrionMesh.Envelope(it.getString("from"), it.getString("action"), it.optString("body"), it.getString("nonce"), it.getLong("ts"), it.getString("sig")) }
    }.getOrNull()

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Accepts connections; [peerFor] maps a node id to its guard, [handle] runs an already-verified request. */
    class Server(port: Int, private val peerFor: (String) -> Pair<String, OrionMesh.Guard>?, private val handle: (String, String, String) -> String) {
        private val socket = ServerSocket(port)
        val port: Int get() = socket.localPort
        @Volatile private var open = true

        fun start(): Server { Thread({ while (open) { val s = try { socket.accept() } catch (_: Exception) { break }; Thread({ serve(s) }, "orion-mesh-conn").start() } }, "orion-mesh").start(); return this }
        fun stop() { open = false; runCatching { socket.close() } }

        private fun serve(s: Socket) = s.use {
            it.soTimeout = 150_000
            val reply = try {
                val env = it.getInputStream().bufferedReader().readLine()?.let(::decode)
                val peer = env?.let { e -> peerFor(e.from) }
                if (env == null || peer == null) JSONObject().put("ok", false).put("result", "unknown node")
                else when (val v = peer.second.check(env, System.currentTimeMillis())) {
                    OrionMesh.Verdict.OK -> JSONObject().put("ok", true).put("result", runCatching { handle(peer.first, env.action, env.body) }.getOrElse { e -> "error: ${e.message}" })
                    else -> JSONObject().put("ok", false).put("result", "rejected: $v")
                }
            } catch (e: Exception) { JSONObject().put("ok", false).put("result", "error: ${e.message}") }
            it.getOutputStream().apply { write((reply.toString() + "\n").toByteArray()); flush() }
        }
    }

    /** Sends one signed request and returns the peer's reply text. */
    fun request(host: String, port: Int, secret: ByteArray, myId: String, action: String, body: String, timeoutMs: Int = 150_000): String =
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), 8_000); s.soTimeout = timeoutMs
            val env = OrionMesh.sign(secret, myId, action, body, System.currentTimeMillis(), java.util.UUID.randomUUID().toString())
            s.getOutputStream().apply { write((encode(env) + "\n").toByteArray()); flush() }
            val line = s.getInputStream().bufferedReader().readLine() ?: return "error: peer closed the connection"
            JSONObject(line).let { (if (it.optBoolean("ok")) "" else "error: ") + it.optString("result") }
        }

    // ---- device-side store and tool ----

    private fun sp(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun nodeId(c: Context): String = sp(c).getString("node", null) ?: java.util.UUID.randomUUID().toString().take(8).also { sp(c).edit().putString("node", it).apply() }

    fun peers(c: Context): List<Peer> {
        val arr = runCatching { JSONArray(sp(c).getString("peers", "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            Peer(o.getString("id"), o.getString("name"), o.optString("host"), o.getString("secret"),
                o.optString("scopes").split(",").filter { it.isNotBlank() }.toSet())
        }
    }

    private fun save(c: Context, list: List<Peer>) = sp(c).edit().putString("peers", JSONArray(list.map {
        JSONObject().put("id", it.id).put("name", it.name).put("host", it.host).put("secret", it.secretHex).put("scopes", it.scopes.joinToString(","))
    }).toString()).apply()

    private var server: Server? = null
    private val guards = HashMap<String, OrionMesh.Guard>()

    @Synchronized fun startServer(c: Context) {
        if (server != null || peers(c).isEmpty()) return
        val app = c.applicationContext
        server = runCatching {
            Server(PORT, { id ->
                peers(app).firstOrNull { it.id == id }?.let { p -> p.name to synchronized(guards) { guards.getOrPut(id + p.scopes) { OrionMesh.Guard(unhex(p.secretHex), p.scopes) } } }
            }) { peer, action, body -> handleRequest(peer, action, body) }.start()
        }.getOrNull()
    }

    private fun handleRequest(peer: String, action: String, body: String): String {
        if (action == "ping") return "pong from this Orion"
        if (action != "task.submit") return "error: '$action' is not supported by this build yet"
        val svc = PhoneAiService.instance ?: return "error: Orion's service is not running on that device"
        val latch = java.util.concurrent.CountDownLatch(1)
        var out = "no answer"
        svc.executeSharedToolRequest(body, "Mesh · $peer") { out = it; latch.countDown() }
        latch.await(140, java.util.concurrent.TimeUnit.SECONDS)
        return out
    }

    fun run(c: Context, a: JSONObject): String {
        val me = nodeId(c)
        return when (a.optString("action").lowercase()) {
            "status" -> "this node: id=$me, listening on port $PORT, paired peers: " + (peers(c).joinToString { "${it.name}(${it.scopes.joinToString("+")})" }.ifBlank { "none" })
            "code" -> "pairing code: ${OrionMesh.newPairingCode()}  (enter the same code and this node id=$me on the other device)"
            "pair" -> {
                val name = a.optString("name").trim().ifBlank { return "error: name required" }
                val other = a.optString("node_id").trim().ifBlank { return "error: node_id of the other device required" }
                val code = a.optString("code").trim().takeIf { Regex("\\d{6}").matches(it) } ?: return "error: 6-digit code required"
                val host = a.optString("host").trim()
                val p = Peer(other, name, host, hex(OrionMesh.deriveSecret(code, me, other)), OrionMesh.DEFAULT_SCOPES)
                save(c, peers(c).filterNot { it.id == other } + p)
                startServer(c)
                "paired with $name. It can ping, read memory and submit tasks. Grant more with action=grant scope=shell.run name=$name."
            }
            "grant" -> {
                val scope = a.optString("scope"); val name = a.optString("name")
                if (scope !in OrionMesh.ALL_SCOPES) return "error: scope must be one of ${OrionMesh.ALL_SCOPES.joinToString()}"
                val list = peers(c)
                val p = list.firstOrNull { it.name == name } ?: return "error: no paired peer named '$name'"
                save(c, list.map { if (it.id == p.id) it.copy(scopes = it.scopes + scope) else it })
                "granted $scope to $name"
            }
            "forget" -> { val n = a.optString("name"); save(c, peers(c).filterNot { it.name == n }); "forgot $n" }
            "send" -> {
                val p = peers(c).firstOrNull { it.name == a.optString("name") } ?: return "error: no paired peer named '${a.optString("name")}'"
                if (p.host.isBlank()) return "error: no host saved for ${p.name}; pair again with host=<ip>"
                val act = a.optString("what").ifBlank { "task.submit" }
                runCatching { request(p.host, PORT, unhex(p.secretHex), me, act, a.optString("body"), 150_000) }.getOrElse { "error: could not reach ${p.name} (${it.message})" }
            }
            else -> "error: action must be status, code, pair, grant, forget or send"
        }
    }
}
