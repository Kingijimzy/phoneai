package com.phoneai.app

import android.content.Context

/**
 * Small, local-only context bridge from the canonical TradingDesk store into the central brain.
 * It never fetches prices/news and is included only for trading-relevant requests.
 */
internal object TradingDeskContext {
    private val tradingIntent = Regex(
        "(?i)\\b(trad(?:e|ing)|forex|fx|crypto|bitcoin|btc|ethereum|eth|bybit|deriv|setup|watchlist|playbook|position|portfolio|market bias|liquidity|smc|order block|fair value gap|fvg|journal|trade review|risk reward|\br:r\b)\\b"
    )
    private val reviewIntent = Regex("(?i)\\b(journal|review|mistake|habit|performance|results|history|win rate|drawdown)\\b")

    fun promptBlock(context: Context, request: String?): String? {
        if (request.isNullOrBlank() || !tradingIntent.containsMatchIn(request)) return null
        val symbols = runCatching { TradingDesk.followSymbols(context).take(12) }.getOrDefault(emptyList())
        val strategies = runCatching { TradingDesk.strategies(context).take(8) }.getOrDefault(emptyList())
        val pending = runCatching { TradingDesk.pending(context).take(5) }.getOrDefault(emptyList())
        val readOnly = runCatching { Prefs.tradingReadOnly(context) }.getOrDefault(true)
        val testnet = runCatching { Prefs.bybitTestnet(context) }.getOrDefault(true)
        val research = runCatching { AgentPolicy.enabled(context, "trading_research") }.getOrDefault(false)
        val execution = runCatching { AgentPolicy.enabled(context, "trading_execution") }.getOrDefault(false)
        val hasKeys = runCatching { Bybit.hasKeys(context) }.getOrDefault(false)
        return buildString {
            append("TRADING DESK CONTEXT — local saved state only; not live market evidence.\n")
            append("Watchlist: ").append(symbols.joinToString(", ").ifBlank { "empty / not configured" }).append(".\n")
            append("Accepted strategies: ")
            if (strategies.isEmpty()) append("none saved")
            else append(strategies.joinToString("; ") { strategy ->
                strategy.name.take(70) + " [" + strategy.instruments.take(6).joinToString(", ") + "; " +
                    strategy.timeframes.take(4).joinToString(", ") + "; min R:R " + strategy.minRewardToRisk + "]"
            })
            append(".\n")
            append("Pending strategy proposals: ")
            append(if (pending.isEmpty()) "none" else pending.joinToString("; ") { it.name.take(70) })
            append(".\n")
            append("Local permissions/config: research pack ").append(if (research) "enabled" else "disabled")
            append("; execution pack ").append(if (execution) "enabled" else "disabled")
            append("; trading ").append(if (readOnly) "READ-ONLY" else "order flow configured")
            append("; account mode ").append(if (testnet) "testnet/practice" else "real account")
            append("; Bybit credentials ").append(if (hasKeys) "configured (never expose)" else "not configured")
            append("; kill switch ").append(if (TradingDesk.killSwitch(context)) "ON" else "OFF").append(".\n")
            if (reviewIntent.containsMatchIn(request)) {
                val evidence = runCatching { TradingDesk.evidenceText(context) }.getOrDefault("Performance evidence unavailable.")
                append("Saved performance evidence (bounded; sample size must be assessed): ")
                    .append(evidence.take(700)).append("\n")
            }
            append("Freshness rule: these are preferences and saved records, not current prices, candles, news, positions or economic releases. Fetch current evidence through registered read-only trading tools before making a live-market claim. Never infer that configured credentials authorize an order. Real orders require all existing capability, read-only, risk and explicit-confirmation gates.")
        }.take(1900)
    }
}
