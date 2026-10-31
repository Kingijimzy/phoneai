package com.phoneai.app

import kotlin.math.abs

/**
 * Simulated broker for practice and for grading strategies before real money is involved.
 * Fills at the price given, resolves stops/targets from candles (stop first when one candle touches both,
 * the conservative choice), and records every result in R multiples. Pure; no Android types.
 */
internal object PaperBroker {
    data class Position(
        val id: Int, val symbol: String, val long: Boolean, val usd: Double,
        val entry: Double, val stop: Double, val target: Double?, val setup: String, val openedAt: Long
    )

    data class Closed(
        val id: Int, val symbol: String, val long: Boolean, val setup: String,
        val entry: Double, val exit: Double, val reason: String, val pnlUsd: Double, val rMultiple: Double, val closedAt: Long
    )

    data class State(
        val startBalance: Double,
        val balance: Double,
        val open: List<Position> = emptyList(),
        val closed: List<Closed> = emptyList(),
        val nextId: Int = 1
    ) {
        val equityClosedOnly: Double get() = balance
    }

    fun start(balance: Double) = State(balance, balance)

    fun open(s: State, symbol: String, long: Boolean, usd: Double, entry: Double, stop: Double, target: Double?, setup: String, now: Long): State {
        require(entry > 0 && usd > 0) { "entry and size must be positive" }
        return s.copy(
            open = s.open + Position(s.nextId, symbol, long, usd, entry, stop, target, setup, now),
            nextId = s.nextId + 1
        )
    }

    /** Applies one candle (high/low/close) for [symbol]; closes any position whose stop or target it touched. */
    fun onCandle(s: State, symbol: String, high: Double, low: Double, now: Long): State {
        var st = s
        for (p in s.open.filter { it.symbol == symbol }) {
            val stopHit = if (p.long) low <= p.stop else high >= p.stop
            val targetHit = p.target != null && (if (p.long) high >= p.target else low <= p.target)
            when {
                stopHit -> st = close(st, p, p.stop, "stop", now)
                targetHit -> st = close(st, p, p.target!!, "target", now)
            }
        }
        return st
    }

    fun closeAt(s: State, id: Int, price: Double, now: Long): State {
        val p = s.open.firstOrNull { it.id == id } ?: return s
        return close(s, p, price, "manual", now)
    }

    private fun close(s: State, p: Position, exit: Double, reason: String, now: Long): State {
        val move = (if (p.long) exit - p.entry else p.entry - exit) / p.entry
        val pnl = p.usd * move
        val riskMove = abs(p.entry - p.stop) / p.entry
        val r = if (riskMove > 0) move / riskMove else 0.0
        return s.copy(
            balance = s.balance + pnl,
            open = s.open.filterNot { it.id == p.id },
            closed = s.closed + Closed(p.id, p.symbol, p.long, p.setup, p.entry, exit, reason, pnl, r, now)
        )
    }
}

/** Performance maths over closed trades, plus a sample-size-aware verdict so Orion never "learns" from noise. */
internal object TradingStats {
    data class Summary(
        val trades: Int, val wins: Int, val winRate: Double, val avgR: Double,
        val profitFactor: Double?, val expectancyR: Double, val maxDrawdownR: Double, val longestLossStreak: Int
    )

    fun summarize(rs: List<Double>): Summary {
        if (rs.isEmpty()) return Summary(0, 0, 0.0, 0.0, null, 0.0, 0.0, 0)
        val wins = rs.count { it > 0 }
        val grossWin = rs.filter { it > 0 }.sum()
        val grossLoss = -rs.filter { it < 0 }.sum()
        var peak = 0.0; var eq = 0.0; var dd = 0.0; var streak = 0; var longest = 0
        for (r in rs) {
            eq += r; if (eq > peak) peak = eq; if (peak - eq > dd) dd = peak - eq
            if (r < 0) { streak++; if (streak > longest) longest = streak } else streak = 0
        }
        val avg = rs.average()
        return Summary(rs.size, wins, wins.toDouble() / rs.size, avg, if (grossLoss > 0) grossWin / grossLoss else null, avg, dd, longest)
    }

    enum class Verdict { TOO_FEW_TRADES, PROMISING, KEEP_WATCHING, WEAK }

    const val MIN_TRADES = 20

    /** No adoption or rejection from a thin sample: below [MIN_TRADES] the answer is always TOO_FEW_TRADES. */
    fun verdict(s: Summary): Verdict = when {
        s.trades < MIN_TRADES -> Verdict.TOO_FEW_TRADES
        s.expectancyR >= 0.2 && (s.profitFactor ?: 0.0) >= 1.3 -> Verdict.PROMISING
        s.expectancyR <= -0.1 || (s.profitFactor != null && s.profitFactor < 0.9) -> Verdict.WEAK
        else -> Verdict.KEEP_WATCHING
    }

    fun bySetup(closed: List<PaperBroker.Closed>): Map<String, Summary> =
        closed.groupBy { it.setup }.mapValues { (_, v) -> summarize(v.map { it.rMultiple }) }

    fun text(s: Summary): String =
        if (s.trades == 0) "No closed trades yet."
        else "${s.trades} trades, win rate ${"%.0f".format(s.winRate * 100)}%, average ${"%.2f".format(s.avgR)}R, " +
            "profit factor ${s.profitFactor?.let { "%.2f".format(it) } ?: "n/a"}, max drawdown ${"%.1f".format(s.maxDrawdownR)}R, longest loss streak ${s.longestLossStreak}."
}
