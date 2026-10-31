package com.phoneai.app

import java.util.Locale

/** The user's strategy, as rules Orion can match setups against. Edited only by the user or with their approval. */
data class StrategySpec(
    val name: String,
    val instruments: List<String> = emptyList(),   // empty = any
    val timeframes: List<String> = emptyList(),    // empty = any
    val setupTypes: List<String> = emptyList(),    // empty = any
    val minRewardToRisk: Double = 1.5,
    val riskPct: Double = 1.0,
    val newsFilter: Boolean = true,
    val notes: String = ""
)

internal object SetupMatcher {
    data class Match(val setup: Setup, val strategy: String, val score: Int, val notes: List<String>)

    /**
     * Ranks setups against strategies: instrument/timeframe/type must fit, reward-to-risk must meet the strategy's
     * minimum, high-impact news excludes the setup, and natural trading hours add a little score.
     * Returns the best strategy match per setup, best first.
     */
    fun rank(
        setups: List<Setup>,
        strategies: List<StrategySpec>,
        utcHour: Int,
        newsBlocked: (symbol: String) -> Boolean,
        winRateOf: (type: String) -> Double? = { null }
    ): List<Match> {
        val out = ArrayList<Match>()
        for (s in setups) {
            if (newsBlocked(s.symbol)) continue
            var best: Match? = null
            for (st in strategies) {
                if (st.instruments.isNotEmpty() && st.instruments.none { it.equals(s.symbol, true) }) continue
                if (st.timeframes.isNotEmpty() && st.timeframes.none { it.equals(s.tf, true) }) continue
                if (st.setupTypes.isNotEmpty() && st.setupTypes.none { it.equals(s.type, true) }) continue
                if (s.rr() < st.minRewardToRisk) continue
                var score = s.score
                val notes = ArrayList<String>()
                if (st.instruments.isNotEmpty()) { score += 8; notes += "on your ${st.name} list" }
                if (MarketSessions.inPreferredSession(s.symbol, utcHour)) { score += 5; notes += "in its active session" } else notes += "outside its usual hours"
                winRateOf(s.type)?.let { wr ->
                    if (wr >= 0.6) { score += 6; notes += "this setup type has worked for you (${"%.0f".format(wr * 100)}%)" }
                    else if (wr < 0.35) { score -= 10; notes += "this setup type has been losing for you (${"%.0f".format(wr * 100)}%)" }
                }
                val m = Match(s, st.name, score, notes)
                if (best == null || m.score > best.score) best = m
            }
            best?.let { out += it }
        }
        return out.sortedByDescending { it.score }
    }

    fun text(m: Match): String =
        "${m.setup.side.uppercase(Locale.US)} ${m.setup.symbol} ${m.setup.tf} (${m.setup.type}) score ${m.score} via ${m.strategy}: " +
            "entry ${Ta.price(m.setup.entry)}, stop ${Ta.price(m.setup.stop)}, target ${Ta.price(m.setup.target)}, R:R ${"%.1f".format(m.setup.rr())}. ${m.notes.joinToString("; ")}."
}
