package com.phoneai.app

import kotlin.math.abs

/**
 * One risk gate for every order Orion could place or simulate (Bybit crypto, Deriv/forex, paper).
 * Pure rules, no Android types. The gate can only say "this order fits your limits" or "blocked, here is why";
 * it never places anything, and real-money orders still need the user's explicit approval each time.
 */
internal object TradingRisk {
    enum class Mode {
        /** Look and advise only. */ ANALYZE_ONLY,
        /** Simulated fills; nothing reaches an exchange. */ PAPER,
        /** Real or testnet orders, each shown to the user for approval first. */ ASK_EACH
    }

    data class Limits(
        val maxOrderUsd: Double = 100.0,
        val maxRiskPct: Double = 1.0,
        val maxDailyLossPct: Double = 3.0,
        val maxOpenPositions: Int = 3,
        val maxTradesPerDay: Int = 6,
        val lossStreakPause: Int = 3,
        val pauseMinutes: Int = 60,
        val minRewardToRisk: Double = 1.5
    )

    data class Account(
        val equity: Double,
        val dayStartEquity: Double,
        val openPositions: Int,
        val tradesToday: Int,
        val consecutiveLosses: Int = 0,
        val lastLossAtMillis: Long = 0L
    )

    data class Order(val symbol: String, val side: String, val usd: Double, val entry: Double, val stop: Double, val target: Double? = null) {
        val isLong: Boolean get() = side.equals("long", true) || side.equals("buy", true)
    }

    data class Verdict(
        val allowed: Boolean,
        val reasons: List<String>,
        val riskUsd: Double,
        val riskPct: Double,
        val rewardToRisk: Double?,
        val suggestedUsd: Double?
    )

    /** Notional dollars to open so that hitting the stop loses exactly [riskPct] of [equity]. */
    fun sizeForRisk(equity: Double, riskPct: Double, entry: Double, stop: Double): Double {
        val move = abs(entry - stop) / entry
        if (equity <= 0 || riskPct <= 0 || entry <= 0 || move <= 0) return 0.0
        return equity * (riskPct / 100.0) / move
    }

    fun evaluate(
        order: Order,
        account: Account,
        limits: Limits,
        nowMillis: Long,
        killSwitch: Boolean = false,
        newsBlackout: Boolean = false
    ): Verdict {
        val reasons = ArrayList<String>()
        val move = if (order.entry > 0) abs(order.entry - order.stop) / order.entry else 0.0
        val riskUsd = order.usd * move
        val riskPct = if (account.equity > 0) riskUsd / account.equity * 100.0 else 100.0
        val rr = order.target?.let { t ->
            val risk = abs(order.entry - order.stop)
            if (risk > 0) abs(t - order.entry) / risk else null
        }

        if (killSwitch) reasons += "The kill switch is on; no new orders until you turn it off."
        if (order.entry <= 0 || order.usd <= 0) reasons += "Order size and entry price must be positive."
        if (order.isLong && order.stop >= order.entry) reasons += "For a long, the stop must be below the entry."
        if (!order.isLong && order.stop <= order.entry) reasons += "For a short, the stop must be above the entry."
        order.target?.let { t ->
            if (order.isLong && t <= order.entry) reasons += "For a long, the target must be above the entry."
            if (!order.isLong && t >= order.entry) reasons += "For a short, the target must be below the entry."
        }
        if (move <= 0) reasons += "A stop loss is required; this order has none."
        if (order.usd > limits.maxOrderUsd) reasons += "Size ${money(order.usd)} is above your max order ${money(limits.maxOrderUsd)}."
        if (riskPct > limits.maxRiskPct) reasons += "Risk ${pct(riskPct)} of the account is above your limit of ${pct(limits.maxRiskPct)}."
        if (rr != null && rr < limits.minRewardToRisk) reasons += "Reward to risk ${"%.2f".format(rr)} is below your minimum ${"%.2f".format(limits.minRewardToRisk)}."
        if (account.openPositions >= limits.maxOpenPositions) reasons += "You already have ${account.openPositions} open positions (limit ${limits.maxOpenPositions})."
        if (account.tradesToday >= limits.maxTradesPerDay) reasons += "You have taken ${account.tradesToday} trades today (limit ${limits.maxTradesPerDay})."
        if (account.dayStartEquity > 0) {
            val dayLossPct = (account.dayStartEquity - account.equity) / account.dayStartEquity * 100.0
            if (dayLossPct >= limits.maxDailyLossPct) reasons += "Today's loss ${pct(dayLossPct)} reached your daily limit of ${pct(limits.maxDailyLossPct)}; stop for the day."
        }
        if (account.consecutiveLosses >= limits.lossStreakPause) {
            val resume = account.lastLossAtMillis + limits.pauseMinutes * 60_000L
            if (nowMillis < resume) reasons += "${account.consecutiveLosses} losses in a row; paused for ${((resume - nowMillis) / 60_000L + 1)} more minute(s)."
        }
        if (newsBlackout) reasons += "A high-impact news event is close; new orders are paused around it."

        val suggested = if (move > 0 && account.equity > 0) {
            minOf(limits.maxOrderUsd, sizeForRisk(account.equity, limits.maxRiskPct, order.entry, order.stop)).takeIf { it > 0 }
        } else null
        return Verdict(reasons.isEmpty(), reasons, riskUsd, riskPct, rr, suggested)
    }

    private fun money(v: Double) = "%.0f dollars".format(v)
    private fun pct(v: Double) = "%.1f%%".format(v)
}
