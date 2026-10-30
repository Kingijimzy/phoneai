package com.phoneai.app

import kotlin.math.abs

/**
 * Walk-forward simulation of a strategy on historical candles. At each closed candle it asks the same setup detector the
 * live scanner uses, keeps only setups the strategy allows, takes the best one when flat, and resolves it candle by
 * candle (stop first when one candle touches both, the conservative choice). Costs are charged so results are not flattering.
 * Past results do not predict future ones; sample size is reported next to every verdict.
 */
internal object StrategyBacktest {
    class Trade(val type: String, val side: String, val entryIndex: Int, val exitIndex: Int, val r: Double, val reason: String)

    class Result(val symbol: String, val tf: String, val bars: Int, val trades: List<Trade>) {
        val rs: List<Double> get() = trades.map { it.r }
        val curve: List<Double> get() { var s = 0.0; return trades.map { s += it.r; s } }
        val summary: TradingStats.Summary get() = TradingStats.summarize(rs)
        fun text(): String {
            val s = summary
            val v = TradingStats.verdict(s)
            return "Simulation of ${symbol} ${tf} over $bars candles: " + (if (trades.isEmpty()) "no trades matched the rules." else TradingStats.text(s)) +
                " Costs charged. " + when (v) {
                    TradingStats.Verdict.TOO_FEW_TRADES -> "Too few trades to judge; treat any number as a rough hint."
                    TradingStats.Verdict.PROMISING -> "Promising on this history, but it is one sample of one market."
                    TradingStats.Verdict.WEAK -> "Weak on this history: reconsider the rules before trading them."
                    TradingStats.Verdict.KEEP_WATCHING -> "Mixed: keep watching, no edge proven."
                }
        }
    }

    /** Four candles into one, used as a cheap higher-timeframe trend read. */
    private fun coarse(cs: List<Candle>): List<Candle> = cs.chunked(4).filter { it.size == 4 }.map { g ->
        Candle(g.first().t, g.first().o, g.maxOf { it.h }, g.minOf { it.l }, g.last().c, g.sumOf { it.v })
    }

    fun run(
        symbol: String, tf: String, candles: List<Candle>, strategy: StrategySpec,
        maxHoldBars: Int = 48, costPct: Double = 0.02, warmup: Int = 120, window: Int = 300
    ): Result {
        val trades = ArrayList<Trade>()
        val n = candles.size
        var i = warmup
        while (i < n - 1) {
            val win = candles.subList(maxOf(0, i + 1 - window), i + 1)
            val htf = Ta.trend(coarse(win))
            val pick = Ta.scan(symbol, tf, win, htf)
                .filter { strategy.setupTypes.isEmpty() || strategy.setupTypes.any { t -> t.equals(it.type, true) } }
                .filter { it.rr() >= strategy.minRewardToRisk }
                .maxByOrNull { it.score }
            if (pick == null) { i++; continue }
            val long = pick.side == "long"
            val risk = abs(pick.entry - pick.stop)
            var exit = -1; var exitPx = 0.0; var reason = ""
            var k = i + 1
            while (k < n && k <= i + maxHoldBars) {
                val c = candles[k]
                val stopHit = if (long) c.l <= pick.stop else c.h >= pick.stop
                val targetHit = if (long) c.h >= pick.target else c.l <= pick.target
                if (stopHit) { exit = k; exitPx = pick.stop; reason = "stop"; break }
                if (targetHit) { exit = k; exitPx = pick.target; reason = "target"; break }
                k++
            }
            if (exit < 0) { // timed out or ran out of data: close at the last candle we looked at
                exit = minOf(n - 1, i + maxHoldBars); exitPx = candles[exit].c; reason = "timeout"
            }
            val gross = (if (long) exitPx - pick.entry else pick.entry - exitPx) / risk
            val cost = pick.entry * costPct / 100.0 / risk
            trades += Trade(pick.type, pick.side, i, exit, gross - cost, reason)
            i = exit + 1
        }
        return Result(symbol, tf, n, trades)
    }
}
