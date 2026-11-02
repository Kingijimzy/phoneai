package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject

/** What the background watcher did and when, newest first, kept small. Pure apart from org.json. */
internal object ActivityLog {
    const val MAX = 100
    data class Entry(val at: Long, val title: String, val text: String)

    fun add(existing: List<Entry>, e: Entry, max: Int = MAX): List<Entry> =
        (listOf(e) + existing).take(max)

    fun encode(l: List<Entry>): String {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("t", it.at).put("h", it.title).put("x", it.text)) }
        return a.toString()
    }

    fun decode(s: String?): List<Entry> = try {
        val a = JSONArray(s ?: "[]")
        (0 until a.length()).map { val o = a.getJSONObject(it); Entry(o.optLong("t"), o.optString("h"), o.optString("x")) }
    } catch (_: Exception) { emptyList() }

    fun ago(now: Long, at: Long): String {
        val m = ((now - at) / 60_000L).coerceAtLeast(0)
        return when {
            m < 1 -> "just now"
            m < 60 -> "${m}m ago"
            m < 24 * 60 -> "${m / 60}h ago"
            else -> "${m / (24 * 60)}d ago"
        }
    }
}

/** Plain-language list of what the trading chat can do, for the Desk screen. Every tool here is read-only or needs your approval. */
internal object DeskCapabilities {
    class Item(val name: String, val does: String)
    val items = listOf(
        Item("Calendar & sessions", "Upcoming high-impact news and which forex sessions are open."),
        Item("Setup scan & matcher", "Ranks setups on your watchlist against your saved strategies."),
        Item("Strategy playbook", "Saves strategies from chat or chart screenshots and tracks their results."),
        Item("Simulate & backtest", "Replays a strategy on history with costs and stop-first fills."),
        Item("Forecast", "A lean with compressed odds from your strategy, news and headlines."),
        Item("Risk gate", "Checks stop, risk percent, reward ratio, daily loss, loss streak and news before any order."),
        Item("Practice account", "Paper trades that settle on their own while you are away."),
        Item("Position monitor", "Warns about missing stops, oversized risk, near liquidation and news ahead."),
        Item("Bybit sync & habits", "Imports closed trades and flags overtrading, revenge trades and costly hours."),
        Item("Funding & open interest", "Shows whether the crowd is leaning one way on a crypto pair."),
        Item("Memecoins & wallets", "Read-only watching of public data. Never asks for a key or seed phrase."),
        Item("Voice & companion", "Spoken alerts and replies, and a character that reacts to your PnL."),
        Item("Backup", "Exports and restores your playbook, practice account and journal.")
    )
}
