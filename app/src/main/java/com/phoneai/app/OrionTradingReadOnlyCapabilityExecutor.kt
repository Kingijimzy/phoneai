package com.phoneai.app

import android.content.Context
import org.json.JSONObject

/**
 * Owns local, read-only trading journal/history queries.
 * It never connects to an exchange, predicts a market, or submits/changes orders.
 */
internal object OrionTradingReadOnlyCapabilityExecutor {
    private val supportedTools = setOf("trade_history_summary")

    fun supports(tool: String): Boolean = tool in supportedTools

    fun execute(tool: String, args: JSONObject, context: Context): String? = when (tool) {
        "trade_history_summary" -> {
            val summary = TradeHistoryStore.summaryText(context)
            if (args.optBoolean("recent", false)) {
                val recent = TradeHistoryStore.recentText(context, 15)
                if (recent.isBlank()) summary else "$summary\n\nRecent imported records:\n$recent"
            } else summary
        }
        else -> null
    }
}
