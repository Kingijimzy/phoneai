package com.phoneai.app

import org.json.JSONObject
import java.util.Locale

/**
 * Message builders and parsers for the Deriv WebSocket API (wss://ws.derivws.com/websockets/v3).
 * Pure so they can be tested without a network. NOT verified against a live Deriv account: use a demo token first.
 */
internal object DerivProtocol {
    const val ENDPOINT = "wss://ws.derivws.com/websockets/v3?app_id="
    const val DEFAULT_APP_ID = "1089" // Deriv's public test app id; users can register their own

    /** EURUSD -> frxEURUSD, XAUUSD -> frxXAUUSD. Returns null for anything that is not a forex or metal pair. */
    fun symbolFor(pair: String): String? = FxData.pairOf(pair)?.let { "frx$it" }

    fun authorize(token: String) = JSONObject().put("authorize", token).toString()
    fun balance() = JSONObject().put("balance", 1).put("account", "current").toString()

    /**
     * Multiplier contract with a stop loss and optional take profit, so every Deriv trade has a defined risk.
     * [stopLossUsd] is the most that can be lost (never more than the stake). MULTUP = long, MULTDOWN = short.
     */
    fun proposal(pair: String, long: Boolean, stakeUsd: Double, multiplier: Int, stopLossUsd: Double, takeProfitUsd: Double?, reqId: Int): String? {
        val sym = symbolFor(pair) ?: return null
        if (stakeUsd <= 0 || multiplier <= 0 || stopLossUsd <= 0) return null
        val limit = JSONObject().put("stop_loss", round2(stopLossUsd))
        if (takeProfitUsd != null && takeProfitUsd > 0) limit.put("take_profit", round2(takeProfitUsd))
        return JSONObject().put("proposal", 1).put("amount", round2(stakeUsd)).put("basis", "stake")
            .put("contract_type", if (long) "MULTUP" else "MULTDOWN").put("currency", "USD")
            .put("symbol", sym).put("multiplier", multiplier).put("limit_order", limit).put("req_id", reqId).toString()
    }

    fun buy(proposalId: String, maxPriceUsd: Double, reqId: Int) =
        JSONObject().put("buy", proposalId).put("price", round2(maxPriceUsd)).put("req_id", reqId).toString()

    sealed class Reply {
        data class Authorized(val loginId: String, val isVirtual: Boolean, val balance: Double, val currency: String) : Reply()
        data class Proposal(val id: String, val askPrice: Double, val text: String) : Reply()
        data class Bought(val contractId: Long, val buyPrice: Double, val text: String) : Reply()
        data class Balance(val balance: Double, val currency: String) : Reply()
        data class Error(val code: String, val message: String) : Reply()
        data class Other(val type: String) : Reply()
    }

    fun parse(json: String): Reply {
        val o = try { JSONObject(json) } catch (_: Exception) { return Reply.Error("bad_json", "Unreadable reply from Deriv.") }
        o.optJSONObject("error")?.let { return Reply.Error(it.optString("code"), it.optString("message").take(200)) }
        return when (val type = o.optString("msg_type")) {
            "authorize" -> o.optJSONObject("authorize")?.let {
                Reply.Authorized(it.optString("loginid"), it.optInt("is_virtual") == 1, it.optDouble("balance", 0.0), it.optString("currency", "USD"))
            } ?: Reply.Error("bad_reply", "No account in authorize reply.")
            "balance" -> o.optJSONObject("balance")?.let { Reply.Balance(it.optDouble("balance", 0.0), it.optString("currency", "USD")) } ?: Reply.Error("bad_reply", "No balance in reply.")
            "proposal" -> o.optJSONObject("proposal")?.let {
                Reply.Proposal(it.optString("id"), it.optDouble("ask_price", 0.0), it.optString("longcode").take(200))
            } ?: Reply.Error("bad_reply", "No proposal in reply.")
            "buy" -> o.optJSONObject("buy")?.let {
                Reply.Bought(it.optLong("contract_id"), it.optDouble("buy_price", 0.0), it.optString("longcode").take(200))
            } ?: Reply.Error("bad_reply", "No contract in buy reply.")
            else -> Reply.Other(type)
        }
    }

    private fun round2(v: Double) = String.format(Locale.US, "%.2f", v).toDouble()
}
