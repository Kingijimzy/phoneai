package com.phoneai.app

import java.util.Locale
import kotlin.math.abs

/**
 * Watches open positions the way a careful risk manager would: missing stops, price closing in on stop or target,
 * a winner that could be protected, liquidation close by, losses beyond plan, and high-impact news about to hit.
 * Pure: it only produces alerts, it never touches an order.
 */
internal object PositionMonitor {
    data class Pos(
        val symbol: String, val long: Boolean, val size: Double, val entry: Double, val mark: Double,
        val unrealisedPnl: Double, val stop: Double?, val target: Double?, val liquidation: Double?
    )

    data class Alert(val key: String, val title: String, val text: String, val urgent: Boolean)

    fun assess(
        positions: List<Pos>, equity: Double, maxRiskPct: Double,
        newsSoon: (symbol: String) -> String?
    ): List<Alert> {
        val out = ArrayList<Alert>()
        for (p in positions) {
            val name = p.symbol.removeSuffix("USDT")
            val dir = if (p.long) 1.0 else -1.0
            fun progress(level: Double) = (p.mark - p.entry) * dir / ((level - p.entry) * dir) // 1.0 = at level, negative = wrong way
            if (p.stop == null || p.stop <= 0.0) {
                out += Alert("${p.symbol}|nostop", "$name has no stop loss", "Your ${if (p.long) "long" else "short"} $name is open with no stop. Set one so a bad move has a limit.", true)
            } else {
                val toStop = progress(p.stop)
                if (toStop >= 0.75) out += Alert("${p.symbol}|nearstop", "$name is close to your stop", "Price ${Ta.price(p.mark)} is ${"%.0f".format(Locale.US, toStop * 100)}% of the way to your stop ${Ta.price(p.stop)}.", true)
                val risk = abs(p.entry - p.stop)
                val rNow = if (risk > 0) (p.mark - p.entry) * dir / risk else 0.0
                val stopBeyondEntry = if (p.long) p.stop < p.entry else p.stop > p.entry
                if (rNow >= 1.0 && stopBeyondEntry) out += Alert("${p.symbol}|protect", "$name is up ${"%.1f".format(Locale.US, rNow)}R", "Consider moving your stop to breakeven (${Ta.price(p.entry)}) to protect the trade.", false)
            }
            if (p.target != null && p.target > 0.0 && progress(p.target) >= 0.85) {
                out += Alert("${p.symbol}|neartarget", "$name is near your target", "Price ${Ta.price(p.mark)} is close to target ${Ta.price(p.target)}. Decide: take profit or trail.", false)
            }
            if (p.liquidation != null && p.liquidation > 0.0 && abs(p.mark - p.liquidation) / p.mark < 0.05) {
                out += Alert("${p.symbol}|liq", "$name is near liquidation", "Mark ${Ta.price(p.mark)} is within 5% of liquidation ${Ta.price(p.liquidation)}. Reduce size or add margin.", true)
            }
            if (equity > 0 && p.unrealisedPnl < 0 && -p.unrealisedPnl / equity * 100.0 > maxRiskPct * 1.5) {
                out += Alert("${p.symbol}|bigloss", "$name loss is beyond your plan", "Open loss ${"%.2f".format(Locale.US, p.unrealisedPnl)} is ${"%.1f".format(Locale.US, -p.unrealisedPnl / equity * 100.0)}% of equity; your limit is $maxRiskPct%.", true)
            }
            newsSoon(p.symbol)?.let { out += Alert("${p.symbol}|news|$it", "News soon on an open $name trade", "$it. Consider reducing size or tightening the stop first.", false) }
        }
        return out
    }

    fun summary(positions: List<Pos>, equity: Double): String {
        if (positions.isEmpty()) return "No open positions."
        val total = positions.sumOf { it.unrealisedPnl }
        return positions.joinToString("; ") {
            "${it.symbol} ${if (it.long) "long" else "short"} ${"%+.2f".format(Locale.US, it.unrealisedPnl)}" + (if (it.stop == null || it.stop <= 0.0) " (no stop)" else "")
        } + ". Total open PnL ${"%+.2f".format(Locale.US, total)}" + (if (equity > 0) " (${"%+.1f".format(Locale.US, total / equity * 100.0)}% of equity)" else "") + "."
    }
}
