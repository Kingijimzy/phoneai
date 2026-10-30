package com.phoneai.app

/**
 * Pure risk envelope for permissioned execution.
 * Call before any live order adapter; kill switch and limits always win.
 */
object TradingRiskPolicy {
    data class Limits(
        val killSwitch: Boolean = false,
        val paperMode: Boolean = true,
        val maxNotionalUsd: Double = 50.0,
        val maxDailyLossUsd: Double = 25.0,
        val maxOpenTrades: Int = 3,
        val symbolAllowlist: Set<String> = emptySet()
    )

    data class OrderIntent(
        val instrument: String,
        val notionalUsd: Double,
        val openTrades: Int = 0,
        val realizedLossUsdToday: Double = 0.0
    )

    data class Decision(val allowed: Boolean, val reason: String)

    fun evaluate(limits: Limits, intent: OrderIntent): Decision {
        if (limits.killSwitch) return Decision(false, "Kill switch is on — no orders.")
        if (limits.paperMode) return Decision(false, "Paper mode is on — log the intent only; no live order.")
        val sym = TradingPlaybook.normalizeInstrument(intent.instrument)
        if (limits.symbolAllowlist.isNotEmpty() && sym !in limits.symbolAllowlist.map { TradingPlaybook.normalizeInstrument(it) }) {
            return Decision(false, "Symbol $sym is not on your execution allowlist.")
        }
        if (intent.notionalUsd <= 0) return Decision(false, "Notional must be positive.")
        if (intent.notionalUsd > limits.maxNotionalUsd) {
            return Decision(false, "Notional ${intent.notionalUsd} exceeds max ${limits.maxNotionalUsd} USD.")
        }
        if (intent.openTrades >= limits.maxOpenTrades) {
            return Decision(false, "Open trades ${intent.openTrades} at max ${limits.maxOpenTrades}.")
        }
        if (intent.realizedLossUsdToday >= limits.maxDailyLossUsd) {
            return Decision(false, "Daily loss limit reached (${limits.maxDailyLossUsd} USD).")
        }
        return Decision(true, "Within risk envelope.")
    }

    fun riskCardText(limits: Limits, intent: OrderIntent, side: String, extra: String = ""): String {
        val gate = evaluate(limits, intent)
        return buildString {
            appendLine("RISK CARD")
            appendLine("Symbol: ${intent.instrument} · Side: $side")
            appendLine("Notional: ${intent.notionalUsd} USD (max ${limits.maxNotionalUsd})")
            appendLine("Open trades: ${intent.openTrades}/${limits.maxOpenTrades}")
            appendLine("Realized loss today: ${intent.realizedLossUsdToday} / max ${limits.maxDailyLossUsd}")
            appendLine("Paper: ${limits.paperMode} · Kill switch: ${limits.killSwitch}")
            appendLine("Gate: ${if (gate.allowed) "PASS" else "BLOCK"} — ${gate.reason}")
            if (extra.isNotBlank()) appendLine(extra.trim())
        }.trim()
    }
}
