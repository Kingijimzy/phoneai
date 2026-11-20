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

    /** Pure: [world] is the OrionWorldState JSON. */
    fun assess(tool: String, world: JSONObject): Forecast {
        val network = world.optString("network", "")
        if (tool in needsNetwork && network == "offline")
            return Forecast(Level.BLOCK, "the phone is offline, so $tool cannot reach its service. Skipped to save a timeout; use an offline route or retry when the network is back.")
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
}
