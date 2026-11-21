package com.phoneai.app

import org.json.JSONObject

/**
 * Predictive simulation: before a tool runs, estimate what will happen from the live WorldState and decide.
 * BLOCK only when failure is certain (the tool needs the network and the phone is offline), so no round is wasted on
 * a timeout. WARN when it is risky but possible, and the warning rides on the result so the next decision uses it.
 */
internal object OrionForesight {
    enum class Level { OK, WARN, BLOCK }
    data class Forecast(val level: Level, val consequence: String)

    private val needsNetwork = setOf(
        "research_web", "web_search", "live_web_search", "live_news", "live_sports", "weather", "fetch_url", "web_api",
        "github_public", "github_search_repositories", "image_generate", "mcp_call", "bybit_public_ticker", "self_edit"
    )
    private val heavy = setOf("dev_shell", "spawn_worker", "self_edit", "extension_test", "image_generate")
    private val irreversible = setOf("delete_file", "wipe", "send_message", "whatsapp_send", "sms_send", "place_trade", "bybit_order", "uninstall_app")

    /** Tools that pull the owner's screen into another app or page. Orion solves problems silently instead. */
    internal val switchesScreen = setOf("open_app", "open_deep_link", "whatsapp_compose", "open_file", "open_page", "system_intent", "open_url")
    private val openIntent = Regex("\\b(open|launch|start|go to|take me|show me|navigate|switch to|bring up|pull up|call|dial|text|message|whatsapp|play|watch|play me)\\b", RegexOption.IGNORE_CASE)

    /** Pure: the owner asked, in this request, to be taken somewhere. Background work, retries and error recovery never count. */
    fun ownerWantsScreenChange(request: String): Boolean = openIntent.containsMatchIn(request)

    /** Pure: [world] is the OrionWorldState JSON; [request] is the owner's current request ("" for background work). */
    fun assess(tool: String, world: JSONObject, request: String = ""): Forecast {
        if (tool in switchesScreen && !ownerWantsScreenChange(request))
            return Forecast(Level.BLOCK, "$tool would switch the owner's screen or open another app, and they did not ask to be taken anywhere. Solve it silently: read_screen/look to inspect, research_web for information, dev_shell or the platform tools to fix, ask_agent for help. Only report the blocker if it truly needs the owner." + (OrionStrategy.nextBest(tool, world)?.let { " $it" } ?: ""))
        val network = world.optString("network", "")
        if (tool in needsNetwork && network == "offline")
            return Forecast(Level.BLOCK, "the phone is offline, so $tool cannot reach its service. Skipped to save a timeout; use an offline route or retry when the network is back." + (OrionStrategy.nextBest(tool, world)?.let { " $it" } ?: ""))
        val battery = world.optJSONObject("battery")
        val pct = battery?.optInt("percent", 100) ?: 100
        val charging = battery?.optBoolean("charging", true) ?: true
        if (tool in heavy && pct in 0..14 && !charging)
            return Forecast(Level.WARN, "battery is $pct% and not charging; $tool is heavy and may be cut off. Finish or checkpoint what matters first.")
        val workers = world.optJSONObject("agents")?.optJSONObject("workers")
        if (tool == "spawn_worker" && workers != null && workers.optInt("running") >= workers.optInt("max", 3))
            return Forecast(Level.BLOCK, "all ${workers.optInt("max")} workers are busy; collect or cancel one before starting another.")
        if (tool in irreversible)
            return Forecast(Level.WARN, "$tool cannot be undone; confirm the exact target with the owner unless they already named it.")
        if (tool in needsNetwork && network == "cellular")
            return Forecast(Level.WARN, "on cellular data; large downloads or long sessions cost the owner's data.")
        return Forecast(Level.OK, "")
    }

    fun note(f: Forecast): String = if (f.level == Level.OK) "" else "[foresight] " + f.consequence

    /** Does the actual result match what was predicted? Surprises become lessons, and calibration is visible. */
    internal data class Tally(var totalMs: Long = 0, var ok: Int = 0, var failed: Int = 0, var warned: Int = 0, var warnedAndFailed: Int = 0, var surprises: Int = 0)
    private val ledger = java.util.concurrent.ConcurrentHashMap<String, Tally>()
    @Volatile private var loaded = false
    @Volatile private var lastSaved = 0L

    /** Predictions and outcomes survive process death, so routing and calibration keep learning across sessions. */
    internal fun serialize(): String = org.json.JSONObject().also { o ->
        ledger.forEach { (k, v) -> synchronized(v) { o.put(k, org.json.JSONArray(listOf(v.totalMs, v.ok, v.failed, v.warned, v.warnedAndFailed, v.surprises))) } }
    }.toString()

    internal fun restore(json: String) {
        runCatching {
            val o = org.json.JSONObject(json)
            o.keys().forEach { k -> val a = o.getJSONArray(k)
                ledger.getOrPut(k) { Tally() }.let { t -> synchronized(t) { t.totalMs += a.optLong(0); t.ok += a.optInt(1); t.failed += a.optInt(2); t.warned += a.optInt(3); t.warnedAndFailed += a.optInt(4); t.surprises += a.optInt(5) } } }
        }
    }

    private fun ensureLoaded(c: android.content.Context) {
        if (loaded) return
        synchronized(this) { if (loaded) return; loaded = true
            c.applicationContext.getSharedPreferences("orion_foresight", android.content.Context.MODE_PRIVATE).getString("ledger", null)?.let { restore(it) } }
    }

    private fun save(c: android.content.Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSaved < 5_000) return
        lastSaved = now
        c.applicationContext.getSharedPreferences("orion_foresight", android.content.Context.MODE_PRIVATE).edit().putString("ledger", serialize()).apply()
    }

    /** Pure core: returns a lesson when an OK-forecast tool failed (a wrong prediction), else null. */
    internal fun compare(tool: String, forecast: Level, failed: Boolean, resultHead: String): String? {
        val t = ledger.getOrPut(tool) { Tally() }
        synchronized(t) {
            if (failed) t.failed++ else t.ok++
            if (forecast == Level.WARN) { t.warned++; if (failed) t.warnedAndFailed++ }
            if (forecast == Level.OK && failed) {
                t.surprises++
                return if (t.surprises == 2) "Foresight missed: $tool failed twice with no warning (${resultHead.take(80)}). Check its preconditions before calling it." else null
            }
        }
        return null
    }

    fun stats(): Map<String, Triple<Int, Int, Long>> = ledger.mapValues { Triple(it.value.ok, it.value.failed, it.value.totalMs) }

    fun record(c: android.content.Context, tool: String, forecast: Level, result: String, ms: Long = 0L) {
        ensureLoaded(c)
        ledger.getOrPut(tool) { Tally() }.let { synchronized(it) { it.totalMs += ms } }
        val failed = OrionReflexion.looksFailed(result)
        compare(tool, forecast, failed, result.lineSequence().firstOrNull().orEmpty())?.let { runCatching { OrionLessons.learn(c, it) } }
        runCatching { save(c) }
    }

    fun calibration(): String {
        val rows = ledger.entries.filter { it.value.ok + it.value.failed >= 2 }.sortedByDescending { it.value.surprises }.take(6)
        if (rows.isEmpty()) return "No predictions compared with outcomes yet this session."
        return rows.joinToString("\n") { (k, v) -> "$k: ${v.ok} ok, ${v.failed} failed, ${v.surprises} unforeseen failures" }
    }
}
