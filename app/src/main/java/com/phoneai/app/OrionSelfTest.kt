package com.phoneai.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Live evaluation baseline: every backend is probed for real, with timings, so "works" is measured, not claimed. */
internal object OrionSelfTest {
    data class Probe(val name: String, val ok: Boolean, val ms: Long, val note: String)

    fun line(p: Probe) = (if (p.ok) "PASS " else "FAIL ") + p.name.padEnd(14) + "${p.ms}ms  " + p.note

    fun summary(probes: List<Probe>): String {
        val pass = probes.count { it.ok }
        return "Platform self-test: $pass/${probes.size} passed\n" + probes.joinToString("\n") { line(it) }
    }

    /** Tools that exist in the registry but no capability backend claims; this should always be empty. */
    fun unrouted(): List<String> = ToolRegistry.all.map { it.name }.filter { OrionCapabilityAdapters.route(it) == null }

    private inline fun probe(name: String, block: () -> Pair<Boolean, String>): Probe {
        val t = System.currentTimeMillis()
        val (ok, note) = try { block() } catch (e: Throwable) { false to (e.message ?: e.javaClass.simpleName) }
        return Probe(name, ok, System.currentTimeMillis() - t, note.replace('\n', ' ').take(110))
    }

    fun run(service: PhoneAiService, tools: Tools, python: OrionPythonCapabilityExecutor?, stopped: () -> Boolean): String {
        val out = ArrayList<Probe>()
        out += probe("shell") { val r = tools.shell("echo orion_ok", ""); (r.contains("orion_ok")) to r }
        out += probe("linux (proot)") { val r = tools.shell("uname -m && echo linux_ok", "linux"); r.contains("linux_ok") to r }
        out += probe("python") {
            val r = python?.execute("python_exec", JSONObject().put("code", "print(6*7)"), { true }, stopped) ?: "python executor unavailable"
            r.contains("42") to r
        }
        out += probe("python net") {
            val r = python?.execute("python_exec", JSONObject().put("code", "import urllib.request as u; print(u.urlopen('https://example.com', timeout=8).status)"), { true }, stopped) ?: "python executor unavailable"
            r.contains("200") to r
        }
        out += probe("internet") {
            val c = URL("https://example.com").openConnection() as HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 6000; c.requestMethod = "HEAD"
            val code = c.responseCode; c.disconnect(); (code in 200..399) to "HTTP $code"
        }
        out += probe("telegram") { TelegramBridge.isConfigured(service).let { it to if (it) "configured" else "not configured" } }
        out += probe("social") { true to OrionSocialConnector.connectedSummary(service) }
        out += probe("model") { Nim.lastChatModel().let { it.isNotBlank() to it.ifBlank { "no chat model used yet" } } }
        out += probe("tool routing") { unrouted().let { it.isEmpty() to (if (it.isEmpty()) "${ToolRegistry.all.size} tools, all routed" else "unrouted: " + it.take(8).joinToString()) } }
        out += probe("workers") { true to "${OrionWorkers.runningCount()}/${OrionWorkers.MAX_PARALLEL} running" }
        return summary(out)
    }
}
