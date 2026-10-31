package com.phoneai.app

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.util.Locale

/** Reads the weekly economic calendar published as JSON by the Forex Factory data partner. Parsing is separate from the network call. */
internal object CalendarFeed {
    const val FEED = "https://nfs.faireconomy.media/ff_calendar_thisweek.json"

    fun parse(json: String): List<MarketCalendar.Event> {
        val arr = try { JSONArray(json) } catch (_: Exception) { return emptyList() }
        val out = ArrayList<MarketCalendar.Event>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val at = try { OffsetDateTime.parse(o.optString("date")).toInstant().toEpochMilli() } catch (_: Exception) { continue }
            val impact = when (o.optString("impact").lowercase(Locale.US)) {
                "high" -> MarketCalendar.Impact.HIGH; "medium" -> MarketCalendar.Impact.MEDIUM; "low" -> MarketCalendar.Impact.LOW; else -> MarketCalendar.Impact.HOLIDAY
            }
            out += MarketCalendar.Event(o.optString("title").take(80), o.optString("country").uppercase(Locale.US), at, impact, o.optString("forecast"), o.optString("previous"), o.optString("actual"))
        }
        return out.sortedBy { it.atMillis }
    }

    fun fetchThisWeek(): List<MarketCalendar.Event> {
        val conn = URL(FEED).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 12_000; conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "Orion/1.0")
            if (conn.responseCode !in 200..299) emptyList() else parse(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (_: Exception) { emptyList() } finally { conn.disconnect() }
    }
}
