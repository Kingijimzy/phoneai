package com.phoneai.app

import kotlin.math.abs

/**
 * Combines the technical outlook, what the user's strategy and its simulated record say, and the news/fundamentals
 * tilt into one lean with honest odds. The odds are deliberately compressed (35 to 65): rule-based reads do not
 * justify more, and the drivers are listed so the user can disagree with any of them. Pure.
 */
internal object MarketForecast {
    class Input(
        val symbol: String,
        val technicalBias: Int,          // -1, 0, 1 from MarketOutlook
        val technicalConfidence: Int,    // 0..100
        val strategySide: Int,           // +1 long setup matches now, -1 short, 0 none
        val strategyExpectancyR: Double?, // from simulation, null when unknown
        val strategyTrades: Int,
        val newsTilt: Double             // -1..1 from calendar or headlines
    )

    class Forecast(val lean: Int, val probabilityUp: Int, val composite: Double, val drivers: List<String>) {
        fun text(symbol: String): String {
            val word = when (lean) { 1 -> "bullish lean"; -1 -> "bearish lean"; else -> "no clear lean" }
            return "$symbol: $word, scenario odds ${probabilityUp}% up / ${100 - probabilityUp}% down over the next day or so (heuristic, compressed, not a guarantee). " +
                drivers.joinToString(" ")
        }
    }

    fun forecast(i: Input): Forecast {
        val drivers = ArrayList<String>()
        val tech = i.technicalBias * (i.technicalConfidence / 100.0)
        drivers += when (i.technicalBias) { 1 -> "Trend and momentum point up."; -1 -> "Trend and momentum point down."; else -> "Trend is sideways or mixed." }
        // A strategy edge only counts as much as the evidence behind it.
        val reliability = (i.strategyTrades / 30.0).coerceIn(0.0, 1.0)
        val edge = ((i.strategyExpectancyR ?: 0.0).coerceIn(-1.0, 1.0)) * reliability
        val strat = if (i.strategySide == 0) 0.0 else i.strategySide * (0.4 + 0.6 * edge.coerceAtLeast(-0.4))
        drivers += when {
            i.strategySide == 0 -> "None of your playbook setups is live right now."
            i.strategyTrades < 20 -> "A playbook setup is live, but its simulated record has only ${i.strategyTrades} trades, so it adds little."
            (i.strategyExpectancyR ?: 0.0) > 0 -> "A playbook setup is live and its simulated record is positive."
            else -> "A playbook setup is live, but its simulated record is negative: weight it down."
        }
        drivers += when { i.newsTilt > 0.25 -> "News and data tone is supportive."; i.newsTilt < -0.25 -> "News and data tone is negative."; else -> "News and data tone is neutral." }
        val composite = (0.5 * tech + 0.3 * strat + 0.2 * i.newsTilt).coerceIn(-1.0, 1.0)
        val lean = if (composite > 0.15) 1 else if (composite < -0.15) -1 else 0
        val p = (50 + 20 * composite).toInt().coerceIn(35, 65)
        return Forecast(lean, p, composite, drivers)
    }
}
