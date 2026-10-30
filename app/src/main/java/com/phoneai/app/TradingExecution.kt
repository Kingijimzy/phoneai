package com.phoneai.app

import android.content.Context

/**
 * Single gateway before any live Bybit order.
 * Combines pack enablement, AgentPolicy/GatePolicy, TradingRiskPolicy, and existing Prefs read-only switch.
 */
object TradingExecution {
    data class Prep(
        val ok: Boolean,
        val message: String,
        val riskCard: String = ""
    )

    /**
     * Call before Bybit.order / closeAll. Does not place the order.
     * @param approvedThisAction true only after the user confirmed this exact order.
     */
    fun prepareOrder(
        c: Context,
        instrument: String,
        side: String,
        notionalUsd: Double,
        approvedThisAction: Boolean,
        openTrades: Int = 0,
        realizedLossUsdToday: Double = 0.0
    ): Prep {
        if (!AgentPolicy.enabled(c, "trading_execution")) {
            return Prep(false, AgentPolicy.packOffMessage("trading_execution"))
        }
        if (Prefs.tradingReadOnly(c)) {
            return Prep(false, "Trading is read-only in Trading settings. Enable confirmed order placement first.")
        }
        if (!Bybit.hasKeys(c)) {
            return Prep(false, "No Bybit API key saved. Add Read+Trade keys in Trading settings (prefer testnet first).")
        }
        val gate = AgentPolicy.evaluate(
            c,
            pack = "trading_execution",
            toolId = "bybit_place_order",
            dangerous = true,
            approvedThisAction = approvedThisAction
        )
        if (gate.decision == AgentPolicy.Decision.DENY || gate.decision == AgentPolicy.Decision.PLAN_ONLY ||
            gate.decision == AgentPolicy.Decision.NEED_APPROVAL
        ) {
            return Prep(false, gate.reason)
        }
        val limits = TradingDeskStore.riskLimits(c)
        // When on testnet, paperMode is true in Limits — allow practice orders but label them.
        val effective = if (Prefs.bybitTestnet(c)) {
            limits.copy(paperMode = false, killSwitch = Prefs.tradingReadOnly(c))
        } else {
            limits.copy(paperMode = false, killSwitch = Prefs.tradingReadOnly(c))
        }
        val intent = TradingRiskPolicy.OrderIntent(
            instrument = instrument,
            notionalUsd = notionalUsd,
            openTrades = openTrades,
            realizedLossUsdToday = realizedLossUsdToday
        )
        val risk = TradingRiskPolicy.evaluate(effective, intent)
        val card = TradingRiskPolicy.riskCardText(effective, intent, side, "Mode: ${Bybit.modeText(c)}")
        if (!risk.allowed) return Prep(false, risk.reason, card)
        return Prep(true, "Ready under risk envelope. ${Bybit.modeText(c)}", card)
    }

    /** Places order only after prepareOrder succeeds with approvedThisAction=true. */
    fun placeApproved(
        c: Context,
        symbol: String,
        side: String,
        usd: Double,
        stop: Double,
        target: Double?
    ): String {
        val prep = prepareOrder(c, symbol, side, usd, approvedThisAction = true)
        if (!prep.ok) return "Blocked: ${prep.message}"
        return try {
            val result = Bybit.order(c, symbol, side, usd, stop, target)
            AgentPolicy.log(c, "bybit_place_order", "place", true, "symbol=$symbol side=$side usd=$usd")
            result
        } catch (e: Exception) {
            AgentPolicy.log(c, "bybit_place_order", "place", true, "error=${e.message?.take(120)}")
            "Order failed: ${e.message?.take(160)}"
        }
    }
}
