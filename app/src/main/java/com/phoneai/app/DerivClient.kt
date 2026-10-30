package com.phoneai.app

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Deriv forex execution (multiplier contracts with a stop loss). Demo accounts first: a real account is refused
 * unless the user switched "allow real Deriv account" on. Every trade passes the trading risk gate and an explicit
 * confirmation. NOT verified against a live Deriv account yet; errors from Deriv are shown to the user as they come.
 */
internal object DerivDesk {
    private class Session(appId: String) : AutoCloseable {
        private val inbox = LinkedBlockingQueue<String>()
        private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
        private val ws: WebSocket = http.newWebSocket(Request.Builder().url(DerivProtocol.ENDPOINT + appId).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { inbox.offer(text) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                inbox.offer("""{"error":{"code":"network","message":"${(t.message ?: "connection failed").replace("\"", "'").take(120)}"}}""")
            }
        })

        /** Sends [msg] and waits for the reply of the matching kind; replies of other kinds (ticks, pings) are skipped. */
        fun call(msg: String, timeoutSec: Long = 15): DerivProtocol.Reply {
            ws.send(msg)
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec)
            while (true) {
                val left = end - System.nanoTime()
                if (left <= 0) return DerivProtocol.Reply.Error("timeout", "Deriv did not answer in time.")
                val raw = inbox.poll(left, TimeUnit.NANOSECONDS) ?: continue
                val r = DerivProtocol.parse(raw)
                if (r !is DerivProtocol.Reply.Other) return r
            }
        }

        override fun close() { try { ws.close(1000, null) } catch (_: Exception) { }; http.dispatcher.executorService.shutdown() }
    }

    fun hasToken(c: Context) = Prefs.derivToken(c).isNotBlank()

    private fun authorized(s: Session, c: Context): Any {
        val token = Prefs.derivToken(c)
        if (token.isBlank()) return "No Deriv token saved. Add one in the Trading screen (use a demo account token first)."
        return when (val r = s.call(DerivProtocol.authorize(token))) {
            is DerivProtocol.Reply.Authorized -> r
            is DerivProtocol.Reply.Error -> "Deriv refused the token: ${r.message}"
            else -> "Unexpected reply from Deriv."
        }
    }

    fun status(c: Context): String = try {
        Session(Prefs.derivAppId(c)).use { s ->
            when (val a = authorized(s, c)) {
                is DerivProtocol.Reply.Authorized -> "Deriv ${if (a.isVirtual) "DEMO" else "REAL"} account ${a.loginId}: balance ${"%.2f".format(Locale.US, a.balance)} ${a.currency}."
                else -> a.toString()
            }
        }
    } catch (e: Exception) { "Deriv unreachable: ${e.message ?: e.javaClass.simpleName}" }

    /** Places a multiplier trade for a coach proposal after the gate and a confirmation. Returns what happened. */
    fun trade(c: Context, p: DeskProposals.Paper, confirm: (String) -> Boolean): String {
        if (Prefs.tradingReadOnly(c)) return "Trading is in READ-ONLY mode. Nothing was sent to Deriv."
        if (DerivProtocol.symbolFor(p.symbol) == null) return "Deriv trading here supports forex pairs and gold only."
        val entry = FxData.pairOf(p.symbol)?.let { FxData.candles(it, "15m").lastOrNull()?.c } ?: return "Could not read a live price for ${p.symbol}."
        if (p.target == null) return "A Deriv trade needs a target as well as a stop, so the reward-to-risk can be checked."
        val plan = DerivSizing.plan(p.usd, entry, p.stop, p.target) ?: return "That stop is wider than 5% of price or invalid, so it cannot be sized safely as a multiplier."
        return try {
            Session(Prefs.derivAppId(c)).use { s ->
                val auth = authorized(s, c)
                val a = auth as? DerivProtocol.Reply.Authorized ?: return@use auth.toString()
                if (!a.isVirtual && !Prefs.derivRealAllowed(c)) return@use "This is a REAL Deriv account. Real Deriv trading is off; use a demo account or switch it on in the Trading screen."
                TradingDesk.gateOrder(c, if (p.long) "buy" else "sell", p.symbol, p.usd, entry, p.stop, p.target, a.balance, "deriv")?.let { return@use it }
                val req = DerivProtocol.proposal(p.symbol, p.long, plan.stake, plan.multiplier, plan.stopLossUsd, plan.takeProfitUsd, 1)
                    ?: return@use "Could not build the Deriv request."
                val prop = when (val r = s.call(req)) {
                    is DerivProtocol.Reply.Proposal -> r
                    is DerivProtocol.Reply.Error -> return@use "Deriv rejected the contract: ${r.message}"
                    else -> return@use "Unexpected reply from Deriv."
                }
                val q = "Place this Deriv ${if (a.isVirtual) "DEMO" else "REAL"} trade? ${p.summary()} at about ${Ta.price(entry)}. Stake ${"%.2f".format(Locale.US, plan.stake)} USD x${plan.multiplier}, " +
                    "stop loss ${"%.2f".format(Locale.US, plan.stopLossUsd)} USD" + (plan.takeProfitUsd?.let { ", take profit ${"%.2f".format(Locale.US, it)} USD" } ?: "") + ". ${prop.text}"
                if (!confirm(q)) return@use "Cancelled. Nothing was placed."
                when (val b = s.call(DerivProtocol.buy(prop.id, prop.askPrice, 2))) {
                    is DerivProtocol.Reply.Bought -> { TradingDesk.recordTrade(c); "Deriv ${if (a.isVirtual) "demo" else "real"} trade placed (contract ${b.contractId}) for ${"%.2f".format(Locale.US, b.buyPrice)} USD." }
                    is DerivProtocol.Reply.Error -> "Deriv rejected the order: ${b.message}"
                    else -> "Unexpected reply from Deriv; check your Deriv account before retrying."
                }
            }
        } catch (e: Exception) { "Deriv trade failed: ${e.message ?: e.javaClass.simpleName}. Check your Deriv account before retrying." }
    }
}
