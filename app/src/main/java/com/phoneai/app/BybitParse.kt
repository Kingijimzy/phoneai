package com.phoneai.app

import org.json.JSONObject
import java.security.MessageDigest

/** Turns Bybit V5 JSON rows into the pure types the monitor and habit detector use. */
internal object BybitParse {
    private fun d(o: JSONObject, k: String): Double? = o.optString(k, "").toDoubleOrNull()

    fun position(o: JSONObject): PositionMonitor.Pos? {
        val size = d(o, "size") ?: return null
        val entry = d(o, "avgPrice") ?: return null
        val mark = d(o, "markPrice") ?: entry
        if (size <= 0.0 || entry <= 0.0) return null
        return PositionMonitor.Pos(
            o.optString("symbol"), o.optString("side") == "Buy", size, entry, mark, d(o, "unrealisedPnl") ?: 0.0,
            d(o, "stopLoss")?.takeIf { it > 0.0 }, d(o, "takeProfit")?.takeIf { it > 0.0 }, d(o, "liqPrice")?.takeIf { it > 0.0 }
        )
    }

    /** In closed-pnl rows `side` is the side of the closing order, so a Sell closed a long. */
    fun fill(o: JSONObject): HabitDetector.Fill? {
        val pnl = d(o, "closedPnl") ?: return null
        val at = o.optString("updatedTime", o.optString("createdTime", "")).toLongOrNull() ?: return null
        val qty = d(o, "qty") ?: 0.0
        val entry = d(o, "avgEntryPrice") ?: 0.0
        return HabitDetector.Fill(o.optString("symbol"), o.optString("side") == "Sell", qty * entry, pnl, at)
    }

    fun imported(o: JSONObject, now: Long): ImportedTrade? {
        val f = fill(o) ?: return null
        val id = o.optString("orderId").ifBlank { "${f.symbol}|${f.closedAt}|${f.pnl}" }
        val fp = MessageDigest.getInstance("SHA-256").digest(("bybit|$id").toByteArray()).joinToString("") { "%02x".format(it) }
        return ImportedTrade(fp, f.symbol, if (f.long) "long" else "short", d(o, "qty"), d(o, "avgEntryPrice"), d(o, "avgExitPrice"), f.pnl,
            o.optString("createdTime"), f.closedAt.toString(), "bybit-sync", now)
    }
}
