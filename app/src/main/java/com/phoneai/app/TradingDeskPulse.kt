package com.phoneai.app

import android.content.Context
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Live status lines for the Trading Desk workspace. Read-only; no orders. */
object TradingDeskPulse {
    data class Snapshot(
        val headline: String,
        val detail: String,
        val modeLine: String,
        val packLine: String,
        val playbookLine: String,
        val sessionLine: String,
        val aliveHint: String
    )

    fun snapshot(c: Context): Snapshot {
        val hour = Calendar.getInstance(TimeZone.getTimeZone("UTC")).get(Calendar.HOUR_OF_DAY)
        val session = TradeProposals.sessionAt(hour)
        val testnet = Prefs.bybitTestnet(c)
        val readOnly = Prefs.tradingReadOnly(c)
        val hasKeys = Bybit.hasKeys(c)
        val execOn = AgentPolicy.enabled(c, "trading_execution")
        val researchOn = AgentPolicy.enabled(c, "trading_research")
        val rules = TradingDeskStore.rules(c)
        val enabled = rules.count { it.enabled }
        val pending = TradingDeskStore.candidates(c).size
        val watch = TradingDeskStore.watchSymbols(c)

        val modeLine = buildString {
            append(if (testnet) "TESTNET" else "REAL")
            append(if (readOnly) " · read-only" else " · orders allowed")
            append(if (hasKeys) " · keys on" else " · no keys")
        }
        val packLine = "Research ${if (researchOn) "ON" else "off"} · Execution ${if (execOn) "ON" else "off"}"
        val playbookLine = "$enabled rules live · $pending pending Accept · ${watch.size} watch symbols"
        val sessionLine = "Session: $session"

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
            else -> "Chat with me, scan the list, or Accept a playbook candidate."
        }
        val aliveHint = listOf(
            "Ask for a scan, a bias on BTC, or paste a chart note.",
            "I stay in this desk — research first, orders only with your OK.",
            "Match runs against your watchlist using public data."
        ).random()

        return Snapshot(
            headline = headline,
            detail = detail,
            modeLine = modeLine,
            packLine = packLine,
            playbookLine = playbookLine,
            sessionLine = sessionLine,
            aliveHint = aliveHint
        )
    }

    fun quickQuotes(c: Context, limit: Int = 4): String {
        val syms = TradingDeskStore.watchSymbols(c).ifEmpty {
            listOf("BTCUSDT", "ETHUSDT")
        }.take(limit)
        if (syms.isEmpty()) return "Watchlist empty."
        val parts = ArrayList<String>()
        for (s in syms) {
            try {
                val px = Bybit.price(c, s)
                val ch = Bybit.change24h(c, s)
                parts += String.format(Locale.US, "%s %.4g (%+.1f%%)", s.replace("USDT", ""), px, ch)
            } catch (_: Exception) {
                parts += "$s —"
            }
        }
        return parts.joinToString("  ·  ")
    }
}
