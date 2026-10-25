package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject

/** Structured actions the Trading Coach can request. Trades are only ever proposals the user must confirm. */
object TradeProposals {
    class Trade(val side: String, val symbol: String, val usd: Double, val stop: Double, val target: Double?, val note: String) {
        fun summary(): String = "${side.uppercase()} $symbol  ${"%.0f".format(usd)} USDT  stop ${trim(stop)}" + (target?.let { "  target ${trim(it)}" } ?: "")
    }
    class Alert(val symbol: String, val above: Boolean, val price: Double)
    class Parsed(val visible: String, val trades: List<Trade>, val alerts: List<Alert>)

    // TRADE: long BTCUSDT 200 stop=60000 target=65000 | reason
    private val tradeLine = Regex("(?im)^\\s*TRADE\\s*:\\s*(long|short|buy|sell)\\s+([A-Za-z0-9]{2,12})\\s+\\$?(\\d+(?:\\.\\d+)?)\\s+stop\\s*=?\\s*\\$?(\\d+(?:\\.\\d+)?)(?:\\s+target\\s*=?\\s*\\$?(\\d+(?:\\.\\d+)?))?\\s*(?:\\|\\s*(.*))?$")
    private val alertLine = Regex("(?im)^\\s*ALERT\\s*:\\s*([A-Za-z0-9]{2,12})\\s+(above|below)\\s+\\$?(\\d+(?:\\.\\d+)?)\\s*$")

    fun parse(reply: String, resolveSymbol: (String) -> String? = { Bybit.symbolOf(it) }): Parsed {
        val trades = ArrayList<Trade>()
        for (m in tradeLine.findAll(reply)) {
            val sym = resolveSymbol(m.groupValues[2]) ?: continue
            val side = if (m.groupValues[1].lowercase() in setOf("long", "buy")) "long" else "short"
            val usd = m.groupValues[3].toDoubleOrNull() ?: continue
            val stop = m.groupValues[4].toDoubleOrNull() ?: continue
            val target = m.groupValues[5].toDoubleOrNull()
            if (usd <= 0 || stop <= 0) continue
            trades.add(Trade(side, sym, usd, stop, target, m.groupValues[6].trim().take(200)))
            if (trades.size >= 2) break
        }
        val alerts = ArrayList<Alert>()
        for (m in alertLine.findAll(reply)) {
            val sym = resolveSymbol(m.groupValues[1]) ?: continue
            val price = m.groupValues[3].toDoubleOrNull() ?: continue
            if (price > 0) alerts.add(Alert(sym, m.groupValues[2].lowercase() == "above", price))
            if (alerts.size >= 4) break
        }
        val clean = reply.replace(tradeLine, "").replace(alertLine, "").replace(Regex("\\n{3,}"), "\n\n").trim()
        return Parsed(clean, trades, alerts)
    }

    /** Appends alerts to the same JSON list WatchService already checks. Returns how many were added. */
    fun saveAlerts(c: android.content.Context, alerts: List<Alert>): Int {
        if (alerts.isEmpty()) return 0
        val arr = try { JSONArray(Prefs.alerts(c)) } catch (_: Exception) { JSONArray() }
        var added = 0
        for (a in alerts) {
            if (arr.length() >= 30) break
            arr.put(JSONObject().put("symbol", a.symbol).put("dir", if (a.above) "above" else "below").put("price", a.price))
            added++
        }
        Prefs.setAlerts(c, arr.toString())
        return added
    }

    /** Which major session is open at this UTC hour. */
    fun sessionAt(utcHour: Int): String {
        val open = ArrayList<String>()
        if (utcHour in 0..7) open.add("Asia")
        if (utcHour in 7..15) open.add("London")
        if (utcHour in 12..20) open.add("New York")
        return if (open.isEmpty()) "quiet hours between sessions" else open.joinToString(" + ") + (if (open.size > 1) " overlap (higher volatility)" else " session")
    }

    private fun trim(v: Double): String = if (v >= 100) "%.2f".format(v) else "%.6g".format(v)
}
