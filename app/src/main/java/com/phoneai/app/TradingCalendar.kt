package com.phoneai.app

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Lightweight macro calendar for the desk pulse and coach context.
 * Built-in high-impact events (NFP-style); not a paid calendar feed.
 * Always treat as approximate — verify before trading news.
 */
object TradingCalendar {
    data class Event(
        val name: String,
        val impact: String,
        val region: String,
        val weekdayHint: String,
        val notes: String
    )

    /** Recurring high-impact patterns traders watch (not exact timestamps). */
    private val recurring = listOf(
        Event("US Non-Farm Payrolls (NFP)", "HIGH", "US", "first Friday", "Jobs data — expect FX and index volatility"),
        Event("FOMC decision / press conference", "HIGH", "US", "scheduled Fed days", "Rates path — crypto and FX often move"),
        Event("US CPI", "HIGH", "US", "mid-month", "Inflation print — risk assets sensitive"),
        Event("ECB rate decision", "HIGH", "EU", "scheduled", "EUR pairs focus"),
        Event("BoE rate decision", "HIGH", "UK", "scheduled", "GBP pairs focus"),
        Event("US weekly jobless claims", "MED", "US", "Thursday", "Labour market pulse"),
        Event("US retail sales", "MED", "US", "mid-month", "Growth pulse")
    )

    fun todayHint(): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val day = cal.get(Calendar.DAY_OF_WEEK)
        val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
        val hints = ArrayList<String>()
        if (day == Calendar.FRIDAY && dayOfMonth <= 7) {
            hints += "Possible NFP window (first Friday) — size down or wait for print"
        }
        if (day == Calendar.THURSDAY) {
            hints += "US jobless claims day (typical) — watch USD"
        }
        if (day == Calendar.WEDNESDAY) {
            hints += "Mid-week: check if FOMC/CPI week on your calendar"
        }
        if (hints.isEmpty()) hints += "No built-in high-impact flag for today — still verify your calendar"
        return hints.joinToString(" · ")
    }

    fun promptBlock(): String {
        val sb = StringBuilder()
        sb.append("MACRO CALENDAR (approximate built-in hints — verify exact times before trading news):\n")
        sb.append("Today: ").append(todayHint()).append("\n")
        sb.append("Watch list:\n")
        recurring.take(6).forEach {
            sb.append("- ").append(it.name).append(" [").append(it.impact).append("] ")
                .append(it.region).append(" (").append(it.weekdayHint).append(") — ").append(it.notes).append("\n")
        }
        return sb.toString().trim()
    }

    fun deskLine(): String = "Macro: " + todayHint()

    fun sessionNameUtc(): String {
        val h = Calendar.getInstance(TimeZone.getTimeZone("UTC")).get(Calendar.HOUR_OF_DAY)
        return TradeProposals.sessionAt(h)
    }
}
