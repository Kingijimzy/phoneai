package com.phoneai.app

import java.util.Locale
import kotlin.math.abs

/**
 * Looks for costly trading habits in closed trades: overtrading days, revenge trades after a loss, losing hours,
 * bigger size after losses, and winners cut shorter than losers run. Every finding states its sample size and nothing
 * is claimed under a small sample. Times are the trade close times (the only time every broker export has). Pure.
 */
internal object HabitDetector {
    data class Fill(val symbol: String, val long: Boolean, val notional: Double, val pnl: Double, val closedAt: Long)
    data class Finding(val kind: String, val text: String, val costEstimate: Double)
    class Report(val trades: Int, val findings: List<Finding>) {
        fun text(): String = when {
            trades < MIN_TRADES -> "Only $trades closed trades so far. I need at least $MIN_TRADES to look for habits without guessing; keep trading and syncing."
            findings.isEmpty() -> "Checked $trades closed trades: no costly pattern stands out (overtrading, revenge trades, bad hours, size after losses). Keep the sample growing."
            else -> "Checked $trades closed trades.\n" + findings.joinToString("\n") { "- " + it.text }
        }
    }

    const val MIN_TRADES = 15
    private const val DAY = 86_400_000L

    /** Best-effort time from a CSV cell: epoch millis, ISO with offset, or "yyyy-MM-dd HH:mm[:ss]" read as UTC. Null when unreadable. */
    fun parseTime(raw: String): Long? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        t.toLongOrNull()?.let { return if (t > 100_000_000_000L) it else it * 1000L }
        try { return java.time.OffsetDateTime.parse(t).toInstant().toEpochMilli() } catch (_: Exception) { }
        return try { java.time.LocalDateTime.parse(t.replace(' ', 'T').take(19)).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() } catch (_: Exception) { null }
    }

    fun analyse(fills: List<Fill>, utcOffsetHours: Int = 0): Report {
        val t = fills.sortedBy { it.closedAt }
        if (t.size < MIN_TRADES) return Report(t.size, emptyList())
        val out = ArrayList<Finding>()
        fun money(v: Double) = String.format(Locale.US, "%+.2f", v)
        val shift = utcOffsetHours * 3_600_000L

        // overtrading: days with far more trades than usual, compared by average result per trade
        val byDay = t.groupBy { (it.closedAt + shift) / DAY }
        val counts = byDay.values.map { it.size }.sorted()
        val median = counts[counts.size / 2]
        val heavyLimit = maxOf(5, median * 2)
        val heavy = byDay.values.filter { it.size >= heavyLimit }.flatten()
        val normal = byDay.values.filter { it.size < heavyLimit }.flatten()
        if (heavy.size >= 6 && normal.size >= 6) {
            val h = heavy.map { it.pnl }.average(); val n = normal.map { it.pnl }.average()
            if (h < n && heavy.sumOf { it.pnl } < 0) out += Finding("overtrading",
                "Overtrading: on days with $heavyLimit+ trades your average trade is ${money(h)} versus ${money(n)} on normal days (${heavy.size} vs ${normal.size} trades). Those heavy days cost ${money(heavy.sumOf { it.pnl })} in total.", heavy.sumOf { it.pnl })
        }

        // revenge: a trade that closes soon after a loss and is clearly bigger
        val revenge = ArrayList<Fill>()
        for (i in 1 until t.size) {
            val prev = t[i - 1]; val cur = t[i]
            if (prev.pnl < 0 && cur.closedAt - prev.closedAt <= 30 * 60_000L && prev.notional > 0 && cur.notional >= prev.notional * 1.3) revenge += cur
        }
        if (revenge.size >= 4) {
            val total = revenge.sumOf { it.pnl }
            val wins = revenge.count { it.pnl > 0 }
            if (total < 0) out += Finding("revenge",
                "Revenge trading: ${revenge.size} times you took a bigger trade within 30 minutes of a loss, winning $wins and losing ${total.let { money(it) }} overall.", total)
        }

        // costly hours: 4-hour blocks of the (local) day
        val blocks = t.groupBy { (((it.closedAt + shift) % DAY) / 3_600_000L).toInt() / 4 }
        blocks.entries.filter { it.value.size >= 6 }.map { (b, l) -> Triple(b, l.size, l.sumOf { it.pnl }) }.filter { it.third < 0 }.minByOrNull { it.third }?.let { (b, n, sum) ->
            val rest = t.filter { (((it.closedAt + shift) % DAY) / 3_600_000L).toInt() / 4 != b }
            if (rest.size >= 6 && rest.sumOf { it.pnl } / rest.size > sum / n)
                out += Finding("hours", "Costly hours: trades closed between %02d:00 and %02d:00 lost %s over %d trades (%s average) while the rest average %s.".format(b * 4, b * 4 + 4, money(sum), n, money(sum / n), money(rest.sumOf { it.pnl } / rest.size)), sum)
        }

        // size after a loss
        val afterLoss = ArrayList<Double>(); val afterWin = ArrayList<Double>()
        for (i in 1 until t.size) if (t[i].notional > 0) (if (t[i - 1].pnl < 0) afterLoss else afterWin) += t[i].notional
        if (afterLoss.size >= 6 && afterWin.size >= 6) {
            val a = afterLoss.average(); val w = afterWin.average()
            if (a > w * 1.25) out += Finding("sizing", "Bigger after losses: your average size after a loss is ${"%.0f".format(Locale.US, a)} versus ${"%.0f".format(Locale.US, w)} after a win. Size should not grow because you are down.", 0.0)
        }

        // winners cut short, losers allowed to run (payoff)
        val wins = t.filter { it.pnl > 0 }; val losses = t.filter { it.pnl < 0 }
        if (wins.size >= 6 && losses.size >= 6) {
            val aw = wins.map { it.pnl }.average(); val al = abs(losses.map { it.pnl }.average())
            val winRate = wins.size.toDouble() / (wins.size + losses.size)
            if (al > aw * 1.3 && winRate < 0.65) out += Finding("payoff", "Losers run bigger than winners: average win ${money(aw)} against average loss ${money(-al)} with a ${"%.0f".format(Locale.US, winRate * 100)}% win rate. You need a higher win rate or bigger winners for that to pay.", 0.0)
        }
        return Report(t.size, out.sortedBy { it.costEstimate })
    }
}
