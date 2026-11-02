package com.phoneai.app

import android.content.Context
import org.json.JSONObject

/** Owns trading-desk research, strategy and paper-account operations. Real exchange orders stay in their separately gated, confirmed flows. */
internal object OrionTradingDeskCapabilityExecutor {
    private val supportedTools = setOf(
        "trading_calendar", "trading_sessions", "trade_risk_check", "setup_scan",
        "strategy_backtest", "market_forecast", "market_conditions", "strategy_list",
        "strategy_save", "strategy_record_result", "paper_trade", "trading_kill_switch",
        "positions_monitor", "bybit_sync", "habit_report", "crypto_derivatives",
        "memecoin_check", "memecoin_watch", "wallet_check", "wallet_watch", "chain_overview"
    )
    fun supports(tool: String): Boolean = tool in supportedTools
    fun execute(tool: String, args: JSONObject, context: Context, stopped: () -> Boolean = { false }): String? {
        if (!supports(tool)) return null
        if (stopped()) return "error: task was stopped before the trading desk operation; nothing was changed."
        return TradingDesk.run(context, tool, args)
    }
}
