package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.util.Locale

/**
 * Economic calendar (CPI, NFP, rate decisions...) for crypto and forex. Parsing and the blackout rule are pure;
 * [fetchThisWeek] reads the public weekly feed. Used to warn, rank setups, and pause new orders around big news.
 */
internal object MarketCalendar {
    enum class Impact { LOW, MEDIUM, HIGH, HOLIDAY }

    data class Event(val title: String, val currency: String, val atMillis: Long, val impact: Impact, val forecast: String, val previous: String) {
        val key: String get() = "$currency|$title|$atMillis"
    }

    const val FEED = "https://nfs.faireconomy.media/ff_calendar_thisweek.json"

    fun parse(json: String): List<Event> {
        val arr = try { JSONArray(json) } catch (_: Exception) { return emptyList() }
        val out = ArrayList<Event>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val at = try { OffsetDateTime.parse(o.optString("date")).toInstant().toEpochMilli() } catch (_: Exception) { continue }
            val impact = when (o.optString("impact").lowercase(Locale.US)) {
                "high" -> Impact.HIGH; "medium" -> Impact.MEDIUM; "low" -> Impact.LOW; else -> Impact.HOLIDAY
            }
            out += Event(o.optString("title").take(80), o.optString("country").uppercase(Locale.US), at, impact, o.optString("forecast"), o.optString("previous"))
        }
        return out.sortedBy { it.atMillis }
    }

    /** Currencies that move a symbol: EURUSD -> EUR, USD; BTCUSDT / crypto -> USD. */
    fun currenciesFor(symbol: String): Set<String> {
        val s = symbol.uppercase(Locale.US).replace(Regex("[^A-Z]"), "").removeSuffix("X")
        return when {
            s.endsWith("USDT") -> setOf("USD")
            s.length == 6 -> setOf(s.take(3), s.takeLast(3))
            s.startsWith("XAU") || s.startsWith("XAG") -> setOf("USD")
            else -> setOf("USD")
        }
    }

    fun upcoming(events: List<Event>, now: Long, withinHours: Int, minImpact: Impact = Impact.MEDIUM, currencies: Set<String>? = null): List<Event> =
        events.filter {
            it.atMillis >= now && it.atMillis <= now + withinHours * 3_600_000L &&
                it.impact.ordinal >= minImpact.ordinal && it.impact != Impact.HOLIDAY &&
                (currencies == null || it.currency in currencies)
        }

    /** True when a high-impact event for these currencies is within [beforeMin] ahead or [afterMin] behind. */
    fun inBlackout(events: List<Event>, now: Long, currencies: Set<String>, beforeMin: Int = 30, afterMin: Int = 15): Boolean =
        events.any {
            it.impact == Impact.HIGH && it.currency in currencies &&
                now >= it.atMillis - beforeMin * 60_000L && now <= it.atMillis + afterMin * 60_000L
        }

    fun text(events: List<Event>, now: Long): String =
        if (events.isEmpty()) "No medium or high impact events in that window."
        else events.joinToString("\n") {
            val mins = (it.atMillis - now) / 60_000L
            val eta = if (mins < 60) "in ${mins}m" else "in ${mins / 60}h${mins % 60}m"
            "${it.impact.name} ${it.currency} ${it.title} $eta" +
                (if (it.forecast.isNotBlank() || it.previous.isNotBlank()) " (forecast ${it.forecast.ifBlank { "-" }}, previous ${it.previous.ifBlank { "-" }})" else "")
        }

    fun fetchThisWeek(): List<Event> {
        val conn = URL(FEED).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 12_000; conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "Orion/1.0")
            if (conn.responseCode !in 200..299) emptyList() else parse(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (_: Exception) { emptyList() } finally { conn.disconnect() }
    }
}
