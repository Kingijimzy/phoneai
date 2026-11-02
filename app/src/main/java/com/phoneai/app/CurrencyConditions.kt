package com.phoneai.app

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Reads the economic calendar the way a forex trader does: for each currency, what is coming, and whether the numbers
 * are expected to improve or worsen (forecast vs previous) or have just surprised (actual vs forecast, when published).
 * It describes conditions; it does not predict price. Pure.
 */
internal object CurrencyConditions {
    class Condition(val currency: String, val highAhead: Int, val next: MarketCalendar.Event?, val tilt: Double, val notes: List<String>) {
        fun text(now: Long): String {
            val lean = when { tilt > 0.25 -> "data leans supportive"; tilt < -0.25 -> "data leans weak"; else -> "data mixed or flat" }
            val nx = next?.let { " Next: ${it.title} in ${hours(it.atMillis - now)}" + (if (it.forecast.isNotBlank()) " (forecast ${it.forecast}, previous ${it.previous.ifBlank { "-" }})" else "") + "." }.orEmpty()
            return "$currency: $lean, $highAhead high-impact event${if (highAhead == 1) "" else "s"} ahead.$nx" + (if (notes.isNotEmpty()) " " + notes.take(2).joinToString(" ") else "")
        }
        private fun hours(ms: Long): String { val m = ms / 60_000L; return if (m < 60) "${m}m" else "${m / 60}h${m % 60}m" }
    }

    /** "150K" -> 150000, "3.2%" -> 3.2, "-0.1%" -> -0.1, "1.25M" -> 1.25e6, "$1.2B", "5.1|2.3" (bid-to-cover style) -> 5.1. Null when not numeric. */
    fun number(raw: String): Double? {
        val m = Regex("^\\s*([-+]?\\d+(?:\\.\\d+)?)\\s*([KMBT%]?)").find(raw.replace("$", "").replace(",", "")) ?: return null
        val v = m.groupValues[1].toDoubleOrNull() ?: return null
        return when (m.groupValues[2]) { "K" -> v * 1e3; "M" -> v * 1e6; "B" -> v * 1e9; "T" -> v * 1e12; else -> v }
    }

    /** Indicators where a higher number is bad for the currency. */
    private fun inverse(title: String) = Regex("(?i)unemployment|jobless|claims|deficit").containsMatchIn(title)

    /** +1 supportive, -1 weak, 0 flat/unknown. Uses actual vs forecast when the actual is known, else forecast vs previous. */
    fun direction(e: MarketCalendar.Event): Int {
        val a = number(e.actual); val f = number(e.forecast); val p = number(e.previous)
        val (x, y) = when {
            a != null && f != null -> a to f
            f != null && p != null -> f to p
            else -> return 0
        }
        val rel = abs(y).let { if (it < 1e-9) 1.0 else it }
        if (abs(x - y) / rel < 0.002) return 0
        val d = if (x > y) 1 else -1
        return if (inverse(e.title)) -d else d
    }

    fun assess(events: List<MarketCalendar.Event>, now: Long, currency: String, horizonHours: Int = 168): Condition {
        val rel = events.filter { it.currency == currency && it.impact != MarketCalendar.Impact.HOLIDAY && it.impact != MarketCalendar.Impact.LOW &&
            it.atMillis >= now - 24 * 3_600_000L && it.atMillis <= now + horizonHours * 3_600_000L }
        val high = rel.filter { it.impact == MarketCalendar.Impact.HIGH && it.atMillis >= now }
        var sum = 0.0; var weight = 0.0
        val notes = ArrayList<String>()
        for (e in rel) {
            val w = (if (e.impact == MarketCalendar.Impact.HIGH) 3.0 else 1.0) * (if (number(e.actual) != null) 1.5 else 1.0)
            val d = direction(e)
            if (d != 0) { sum += d * w; weight += w }
            if (number(e.actual) != null && d != 0 && e.atMillis < now) notes += "${e.title} came in ${if (d > 0) "better" else "worse"} than forecast."
        }
        return Condition(currency, high.size, high.minByOrNull { it.atMillis }, if (weight == 0.0) 0.0 else (sum / max(weight, 3.0)).coerceIn(-1.0, 1.0), notes)
    }

    /** Base minus quote: positive favours the pair going up. */
    fun pairTilt(events: List<MarketCalendar.Event>, now: Long, pair: String): Double {
        val c = MarketCalendar.currenciesFor(pair).toList()
        if (c.size < 2) { // gold and silver trade against the dollar: a stronger USD tilt is bearish for them
            val metal = pair.uppercase(Locale.US).let { it.startsWith("XAU") || it.startsWith("XAG") }
            return if (metal) -assess(events, now, "USD").tilt else 0.0
        }
        return (assess(events, now, c[0]).tilt - assess(events, now, c[1]).tilt).coerceIn(-1.0, 1.0)
    }

    fun pairText(events: List<MarketCalendar.Event>, now: Long, pair: String): String {
        val cur = MarketCalendar.currenciesFor(pair).toList()
        val parts = cur.joinToString("\n") { assess(events, now, it).text(now) }
        val t = pairTilt(events, now, pair)
        val net = when { t > 0.2 -> "Net fundamentals mildly favour ${pair} up."; t < -0.2 -> "Net fundamentals mildly favour ${pair} down."; else -> "Net fundamentals are balanced." }
        return "$parts\n$net (calendar expectations only; markets often price these in)."
    }
}

/** Counts bullish and bearish words in headlines. Crude on purpose: it flags tone, it is not analysis. */
internal object HeadlineSentiment {
    private val bull = listOf("etf approv", "inflow", "adoption", "rally", "surge", "record high", "all-time high", "breakout", "bullish", "buy the dip", "accumulat", "rate cut", "upgrade", "partnership", "soar")
    private val bear = listOf("hack", "exploit", "ban", "lawsuit", "sue", "crash", "plunge", "outflow", "liquidat", "bearish", "sell-off", "selloff", "rate hike", "downgrade", "fraud", "collapse", "dump", "probe", "investigation")

    class Tone(val score: Int, val bullish: Int, val bearish: Int, val total: Int) {
        val label: String get() = when { total == 0 -> "no headlines"; score >= 2 -> "positive"; score <= -2 -> "negative"; else -> "mixed" }
        val tilt: Double get() = if (total == 0) 0.0 else (score / max(4.0, total.toDouble())).coerceIn(-1.0, 1.0)
    }

    fun score(headlines: List<String>): Tone {
        var b = 0; var s = 0
        for (h in headlines) {
            val t = h.lowercase(Locale.US)
            val up = bull.count { it in t }; val down = bear.count { it in t }
            if (up > down) b++ else if (down > up) s++
        }
        return Tone(b - s, b, s, headlines.size)
    }
}
