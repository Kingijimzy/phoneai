package com.phoneai.app

import android.content.Context
import org.json.JSONObject

/** Agent-facing trading desk tools. Orders only through TradingExecution after UI confirm. */
internal object TradingToolHandlers {
    fun watchlistList(c: Context): String {
        val syms = TradingDeskStore.watchSymbols(c)
        return if (syms.isEmpty()) "Watchlist is empty." else "Watchlist: " + syms.joinToString(", ")
    }

    fun watchlistSet(c: Context, symbols: String): String {
        val cleaned = symbols.split(",", " ", "\n")
            .map { TradingPlaybook.normalizeInstrument(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(30)
        Prefs.setWatchlist(c, cleaned.joinToString(","))
        return "Watchlist updated: " + cleaned.joinToString(", ")
    }

    fun playbookList(c: Context): String {
        val rules = TradingDeskStore.rules(c)
        val cands = TradingDeskStore.candidates(c)
        val sb = StringBuilder()
        sb.append("Rules (${rules.size}):\n")
        if (rules.isEmpty()) sb.append("(none)\n") else rules.forEach {
            sb.append("- ${it.id} ${if (it.enabled) "ON" else "off"} ${it.instrument} ${it.direction} ${it.name}\n")
        }
        sb.append("Pending candidates (${cands.size}):\n")
        if (cands.isEmpty()) sb.append("(none)") else cands.forEach { (id, cand) ->
            sb.append("- $id ${cand.instrument} ${cand.direction} ${cand.name}\n")
        }
        return sb.toString().trim()
    }

    fun playbookPropose(c: Context, a: JSONObject): String {
        val instrument = TradingPlaybook.normalizeInstrument(a.optString("instrument"))
        if (instrument.isBlank()) return "error: instrument required"
        val direction = TradingPlaybook.parseDirection(a.optString("direction")) ?: TradingPlaybook.Direction.EITHER
        val conditions = a.optString("conditions").split("|", ";", "\n").map { it.trim() }.filter { it.isNotBlank() }
        val cand = TradingPlaybook.Candidate(
            name = a.optString("name").ifBlank { "Setup $instrument" },
            assetClass = TradingPlaybook.parseAssetClass(instrument),
            instrument = instrument,
            direction = direction,
            conditions = conditions,
            sourceNote = a.optString("note").take(400),
            needsUserAccept = true
        )
        val id = TradingDeskStore.proposeCandidate(c, cand)
        return "Candidate $id stored. User must Accept in Trading settings before it matches."
    }

    fun playbookSetEnabled(c: Context, id: String, enabled: Boolean): String {
        if (id.isBlank()) return "error: id required"
        if (TradingDeskStore.rules(c).none { it.id == id }) return "error: unknown rule id"
        TradingDeskStore.setRuleEnabled(c, id, enabled)
        return "Rule $id enabled=$enabled"
    }

    fun matchSetups(c: Context): String {
        val cards = TradingDeskStore.matchWatchlist(c)
        if (cards.isEmpty()) return "No setups matched. Enable playbook rules and keep symbols on the watchlist."
        return cards.take(10).joinToString("\n") {
            "${it.instrument} ${it.direction} score=${"%.2f".format(it.score)} rule=${it.ruleName} (${it.reasons.take(3).joinToString("; ")})"
        }
    }

    fun strategyNote(c: Context, a: JSONObject): String {
        val instrument = TradingPlaybook.normalizeInstrument(a.optString("instrument"))
        if (instrument.isBlank()) return "error: instrument required"
        val levels = a.optString("levels").split(",", " ").map { it.trim() }.filter { it.isNotBlank() }
        val note = TradingPlaybook.StrategyNote(
            instrument = instrument,
            direction = TradingPlaybook.parseDirection(a.optString("direction")),
            levels = levels,
            invalidation = a.optString("invalidation"),
            session = a.optString("session"),
            rawSummary = a.optString("summary").ifBlank { a.optString("note") },
            fromImage = a.optBoolean("from_image", false)
        )
        val cand = TradingPlaybook.candidateFromNote(note, a.optString("name").ifBlank { null })
        val id = TradingDeskStore.proposeCandidate(c, cand)
        return "Strategy candidate $id created for $instrument. Accept it in Trading settings to enable matching."
    }

    fun publicKline(c: Context, symbol: String, interval: String, limit: Int): String {
        val sym = Bybit.symbolOf(symbol) ?: TradingPlaybook.normalizeInstrument(symbol)
        if (sym.isBlank()) return "error: bad symbol"
        return try {
            val candles = Bybit.candles(c, sym, interval.ifBlank { "60" }, limit.coerceIn(5, 200))
            if (candles.isEmpty()) "No klines for $sym"
            else {
                val last = candles.takeLast(5)
                "Last ${last.size} $interval candles for $sym: " + last.joinToString(" | ") {
                    "o=${it.o} h=${it.h} l=${it.l} c=${it.c}"
                }
            }
        } catch (e: Exception) {
            "error: ${e.message?.take(160)}"
        }
    }

    fun placeOrder(c: Context, ui: AgentUi, a: JSONObject): String {
        val symbol = Bybit.symbolOf(a.optString("symbol")) ?: TradingPlaybook.normalizeInstrument(a.optString("symbol"))
        val side = a.optString("side").lowercase().let { if (it in setOf("long", "buy")) "long" else "short" }
        val usd = a.optDouble("usd", 0.0)
        val stop = a.optDouble("stop", 0.0)
        val target = if (a.has("target") && !a.isNull("target")) a.optDouble("target") else null
        if (symbol.isBlank() || usd <= 0 || stop <= 0) return "error: symbol, usd, and stop are required"
        val prep = TradingExecution.prepareOrder(c, symbol, side, usd, approvedThisAction = false)
        if (!prep.ok && !prep.message.contains("approval", ignoreCase = true) && !prep.message.contains("Trusted", ignoreCase = true)) {
            return "Blocked: ${prep.message}\n${prep.riskCard}"
        }
        val card = prep.riskCard.ifBlank {
            TradingRiskPolicy.riskCardText(TradingDeskStore.riskLimits(c), TradingRiskPolicy.OrderIntent(symbol, usd), side)
        }
        val ok = ui.ask("Place $side $symbol for about $usd USDT?\nStop $stop" + (target?.let { " target $it" } ?: "") + "\n\n$card\n\nMode: ${Bybit.modeText(c)}")
        if (!ok) return "error: user cancelled order"
        return TradingExecution.placeApproved(c, symbol, side, usd, stop, target)
    }

    fun cancelOrders(c: Context, ui: AgentUi): String {
        if (!AgentPolicy.enabled(c, "trading_execution")) return AgentPolicy.packOffMessage("trading_execution")
        if (Prefs.tradingReadOnly(c)) return "Trading is read-only."
        if (!ui.ask("Cancel all open Bybit orders? Mode: ${Bybit.modeText(c)}")) return "error: user cancelled"
        return try {
            Bybit.cancelAll(c)
        } catch (e: Exception) {
            "error: ${e.message?.take(160)}"
        }
    }
}
