package com.phoneai.app

import java.util.Locale

/**
 * Economic calendar (CPI, NFP, rate decisions...) for crypto and forex. The blackout rule and filters are pure;
 * CalendarFeed reads the public weekly feed. Used to warn, rank setups, and pause new orders around big news.
 */
internal object MarketCalendar {
    enum class Impact { LOW, MEDIUM, HIGH, HOLIDAY }

    data class Event(val title: String, val currency: String, val atMillis: Long, val impact: Impact, val forecast: String, val previous: String, val actual: String = "") {
        val key: String get() = "$currency|$title|$atMillis"
    }

    /** Currencies that move a symbol: EURUSD -> EUR, USD; BTCUSDT / crypto -> USD. */
    fun currenciesFor(symbol: String): Set<String> {
        val s = symbol.uppercase(Locale.US).replace(Regex("[^A-Z]"), "").removeSuffix("X")
        return when {
            s.endsWith("USDT") -> setOf("USD")
            s.startsWith("XAU") || s.startsWith("XAG") -> setOf("USD")
            s.length == 6 -> setOf(s.take(3), s.takeLast(3))
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

}
