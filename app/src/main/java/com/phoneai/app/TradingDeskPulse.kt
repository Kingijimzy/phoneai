package com.phoneai.app

import android.content.Context
import java.util.Locale

/** Live desk status for the Trading Desk workspace pulse card. */
object TradingDeskPulse {
    data class Snapshot(
        val headline: String,
        val detail: String,
        val modeLine: String,
        val packLine: String,
        val playbookLine: String,
        val sessionLine: String,
        val macroLine: String,
        val aliveHint: String
    )

    fun snapshot(c: Context): Snapshot {
        val researchOn = AgentPolicy.enabled(c, "trading_research")
        val execOn = AgentPolicy.enabled(c, "trading_execution")
        val readOnly = Prefs.tradingReadOnly(c)
        val hasKeys = Bybit.hasKeys(c)
        val testnet = Prefs.bybitTestnet(c)
        val rules = TradingDeskStore.rules(c)
        val enabled = rules.count { it.enabled }
        val pending = TradingDeskStore.candidates(c).size
        val watch = TradingDeskStore.watchSymbols(c)
        val session = TradingCalendar.sessionNameUtc()

        val modeLine = buildString {
            append(if (testnet) "TESTNET" else "REAL")
            append(if (readOnly) " · read-only" else " · orders allowed")
            append(if (hasKeys) " · keys on" else " · no keys")
        }
        val packLine = "Research ${if (researchOn) "ON" else "off"} · Execution ${if (execOn) "ON" else "off"}"
        val playbookLine = "$enabled rules live · $pending pending Accept · ${watch.size} watch symbols"
        val sessionLine = "Session: $session"
        val macroLine = TradingCalendar.deskLine()

        val headline = when {
            !researchOn && !execOn -> "Desk is quiet"
            !hasKeys && !testnet -> "Keys needed for live account"
            pending > 0 -> "$pending strategy note${if (pending == 1) "" else "s"} waiting for you"
            enabled > 0 -> "Watching ${watch.take(3).joinToString(", ")} with $enabled rule${if (enabled == 1) "" else "s"}"
            else -> "Desk ready · teach me a setup"
        }
        val detail = when {
            readOnly && execOn -> "Execution pack is on, but read-only still blocks orders."
            execOn && !readOnly && hasKeys -> "I can propose trades; you still confirm each order."
            else -> "Chat with me, attach a chart, scan crypto/FX, or Accept a playbook candidate."
        }
        val aliveHint = listOf(
            "Attach a TradingView screenshot or paste a setup — I will draft a candidate for Accept.",
            "I watch crypto on Bybit public data and FX via public quotes when you ask.",
            "Orders stay permissioned: pack on + your confirm. Testnet first.",
            "Ask for NFP/macro context, EURUSD bias, or a full watchlist scan."
        ).random()

        return Snapshot(
            headline = headline,
            detail = detail,
            modeLine = modeLine,
            packLine = packLine,
            playbookLine = playbookLine,
            sessionLine = sessionLine,
            macroLine = macroLine,
            aliveHint = aliveHint
        )
    }

    fun quickQuotes(c: Context, limit: Int = 5): String {
        val syms = TradingDeskStore.watchSymbols(c).ifEmpty {
            listOf("BTCUSDT", "ETHUSDT", "EURUSD")
        }.take(limit)
        if (syms.isEmpty()) return "Watchlist empty."
        val parts = ArrayList<String>()
        for (s in syms) {
            val fx = MarketQuotes.resolve(s.replace("/", ""))
            if (fx != null) {
                try {
                    val q = MarketQuotes.quote(fx.first, fx.second)
                    parts += String.format(Locale.US, "%s %.5g", fx.second, q.price)
                } catch (_: Exception) {
                    parts += "$s —"
                }
            } else {
                try {
                    val px = Bybit.price(c, s)
                    val ch = Bybit.change24h(c, s)
                    parts += String.format(Locale.US, "%s %.4g (%+.1f%%)", s.replace("USDT", ""), px, ch)
                } catch (_: Exception) {
                    parts += "$s —"
                }
            }
        }
        return parts.joinToString("  ·  ")
    }
}
