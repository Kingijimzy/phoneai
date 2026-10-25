package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Finds trade setups across your watchlist. Pure maths on candle data; learns from the journal. */
object Scanner {

    fun scan(c: Context): List<Setup> {
        val syms = Prefs.watchlist(c).split(",").map { it.trim().uppercase(Locale.US) }.filter { it.isNotEmpty() }
        val found = ArrayList<Setup>()
        for (sym in syms) {
            try {
                val h4 = Bybit.candles(c, sym, "240", 200)
                val h1 = Bybit.candles(c, sym, "60", 200)
                Journal.evaluate(c, sym, h1)
                val h4c = if (h4.size > 1) h4.subList(0, h4.size - 1) else h4
                val h1c = if (h1.size > 1) h1.subList(0, h1.size - 1) else h1
                val htf = Ta.trend(h4c)
                val all = ArrayList<Setup>()
                all.addAll(Ta.scan(sym, "1h", h1c, htf))
                all.addAll(Ta.scan(sym, "4h", h4c, htf))
                for (s in all) {
                    val wr = Journal.winRate(c, s.type)
                    if (wr != null) {
                        if (wr < 0.35) s.score -= 15
                        if (wr > 0.6) s.score += 8
                    }
                    if (s.score >= 60) found.add(s)
                }
                Thread.sleep(150)
            } catch (e: Exception) {
                // skip this coin, try the next
            }
        }
        found.sortByDescending { it.score }
        return found
    }
}

/** Trading commands: balance, prices, analysis, scanning, alerts and quick orders with safety checks. */
object Trading {

    private fun needKeys(c: Context): String? =
        if (Bybit.hasKeys(c)) null else "I need your Bybit key first. Open Orion, go to the Trading card, and paste your API key and secret."

    private fun fail(e: Exception): String = "Bybit says: " + (e.message ?: "something went wrong")

    private fun num(s: String): Double = s.toDouble()

    private fun analysisInstruction(c: Context): String {
        val lessons = MemoryStore.all(c).filter { it.text.startsWith("Trading lesson") }.sortedBy { it.updated }.takeLast(10)
        val sb = StringBuilder()
        sb.append("You are a disciplined crypto swing-trading mentor speaking aloud. Using ONLY the chart facts given, ")
        sb.append("say the bias, the key levels, one possible plan with entry, stop and target, and what would invalidate it. ")
        sb.append("Under 90 words, plain spoken sentences, no markdown. This is education, not a guarantee. ")
        if (lessons.isNotEmpty()) {
            sb.append("The trader's own lessons to respect: ")
            for (l in lessons) sb.append(l.text.removePrefix("Trading lesson:").trim()).append("; ")
        }
        return sb.toString()
    }

    fun scanText(c: Context): String {
        val found = Scanner.scan(c)
        if (found.isEmpty()) return "I checked your watchlist and there is nothing clean right now. Patience is also a position."
        val sb = StringBuilder()
        sb.append("I found ").append(found.size).append(if (found.size == 1) " setup. " else " setups. ")
        for (s in found.take(3)) {
            sb.append(Ta.describe(s)).append(" ")
            Journal.add(c, s)
        }
        return sb.toString().trim()
    }

    private fun analyze(c: Context, sym: String, ai: (String, String) -> String): String {
        val d1 = Bybit.candles(c, sym, "D", 200)
        val h4 = Bybit.candles(c, sym, "240", 200)
        val h1 = Bybit.candles(c, sym, "60", 200)
        val sb = StringBuilder()
        sb.append(Ta.summary(sym, "daily", d1.dropLast(1))).append("\n")
        sb.append(Ta.summary(sym, "4h", h4.dropLast(1))).append("\n")
        sb.append(Ta.summary(sym, "1h", h1.dropLast(1))).append("\n")
        val htf = Ta.trend(h4.dropLast(1))
        val setups = ArrayList<Setup>()
        setups.addAll(Ta.scan(sym, "1h", h1.dropLast(1), htf))
        setups.addAll(Ta.scan(sym, "4h", h4.dropLast(1), htf))
        if (setups.isNotEmpty()) sb.append("Setups now: ").append(setups.joinToString(" ") { Ta.describe(it) }).append("\n")
        sb.append("24h change: ").append(String.format(Locale.US, "%.1f", Bybit.change24h(c, sym))).append(" percent.")
        val data = sb.toString()
        return try {
            ai(analysisInstruction(c), data)
        } catch (e: Exception) {
            data
        }
    }

    private fun setLimit(c: Context, usd: Float?, risk: Float?): String {
        if (usd != null) Prefs.setMaxOrderUsd(c, usd)
        if (risk != null) Prefs.setMaxRiskPct(c, risk)
        return "Limits are now: max order " + Prefs.maxOrderUsd(c).toInt() + " dollars, max risk " + Prefs.maxRiskPct(c) + " percent of your account."
    }

    private fun alertsArr(c: Context): JSONArray = try {
        JSONArray(Prefs.alerts(c))
    } catch (e: Exception) {
        JSONArray()
    }

    private fun placeOrder(
        c: Context, side: String, usd: Double, sym: String, stop: Double, target: Double?, confirm: (String) -> Boolean
    ): String {
        needKeys(c)?.let { return it }
        if (usd > Prefs.maxOrderUsd(c)) {
            return "That is above your max order of " + Prefs.maxOrderUsd(c).toInt() + " dollars. Say: set max order to a bigger number, if you really want that."
        }
        val px = Bybit.price(c, sym, true)
        val eq = Bybit.equity(c)
        val riskUsd = usd * Math.abs(px - stop) / px
        val riskPct = if (eq > 0) riskUsd / eq * 100.0 else 100.0
        if (riskPct > Prefs.maxRiskPct(c)) {
            val ok = if (eq > 0) (Prefs.maxRiskPct(c) / 100.0 * eq) / (Math.abs(px - stop) / px) else 0.0
            return String.format(
                Locale.US,
                "Too risky. This would risk about %.2f dollars, %.1f percent of your account, and your limit is %.1f percent. A size of about %.0f dollars would fit.",
                riskUsd, riskPct, Prefs.maxRiskPct(c), ok
            )
        }
        val q = StringBuilder()
        q.append("Place this order? ").append(side.uppercase()).append(" ").append(sym)
            .append(", size ").append(String.format(Locale.US, "%.0f", usd)).append(" dollars at about ").append(Ta.price(px))
            .append(", stop ").append(Ta.price(stop))
        if (target != null) q.append(", target ").append(Ta.price(target))
        q.append(String.format(Locale.US, ". Risk about %.2f dollars, %.1f percent of your account. ", riskUsd, riskPct))
        q.append("Mode: ").append(Bybit.modeText(c)).append(".")
        if (!confirm(q.toString())) return "Cancelled. Nothing was placed."
        return Bybit.order(c, sym, side, usd, stop, target)
    }

    /** Entry point for coach-proposed trades: same max-order and max-risk guards plus an explicit confirmation. */
    internal fun placeProposal(c: Context, p: TradeProposals.Trade, confirm: (String) -> Boolean): String =
        try { placeOrder(c, p.side, p.usd, p.symbol, p.stop, p.target, confirm) } catch (e: Exception) { fail(e) }

    /** Returns a spoken answer, or null when the sentence is not a trading command. */
    fun handle(c: Context, t: String, confirm: (String) -> Boolean, ai: (String, String) -> String): String? {
        if (!Regex("balance|wallet|position|trades|price|analy|study|scan|opportunit|setup|buy|long|sell|short|close|cancel|kill|panic|emergency|hunt|watch|alert|journal|bybit|testnet|real money|live|lesson|stats|limit|risk|max").containsMatchIn(t)) return null
        try {
            // ---- account
            if (Regex("^(my |bybit )?(balance|wallet|account balance|bybit balance|how much (money )?do i have)$").matches(t)) {
                needKeys(c)?.let { return it }
                return Bybit.balance(c)
            }
            if (Regex("^(my |open |bybit )?(positions|open trades|open positions|trades)( now)?$").matches(t)) {
                needKeys(c)?.let { return it }
                return Bybit.positions(c)
            }
            if (Regex("^(trading|bybit) (status|mode)$").matches(t)) {
                return "Trading mode: " + Bybit.modeText(c) + ". Max order " + Prefs.maxOrderUsd(c).toInt() + " dollars, max risk " +
                    Prefs.maxRiskPct(c) + " percent. Keys " + (if (Bybit.hasKeys(c)) "saved" else "not saved") +
                    ". Market watch is " + (if (Prefs.watchOn(c)) "on" else "off") + "."
            }

            // ---- prices
            val pr = Regex("^(what('s| is) )?(the )?price (of |for )?([a-z0-9]+)$").find(t) ?: Regex("^([a-z0-9]+) price$").find(t)
            if (pr != null) {
                val sym = Bybit.symbolOf(pr.groupValues.last())
                if (sym != null) {
                    val p = Bybit.price(c, sym)
                    val ch = Bybit.change24h(c, sym)
                    return sym.removeSuffix("USDT") + " is " + Ta.price(p) + " dollars, " + String.format(Locale.US, "%+.1f", ch) + " percent in 24 hours."
                }
            }

            // ---- analysis and scanning
            val an = Regex("^(analy[sz]e|study|outlook for|what do you think (of|about)) ([a-z0-9]+)$").find(t)
            if (an != null) {
                val sym = Bybit.symbolOf(an.groupValues[3])
                if (sym != null) return analyze(c, sym, ai)
            }
            if (Regex("^(scan|find|look for|hunt)( the)?( market| trades| trade| opportunities| trade opportunities| setups)?$").matches(t) && t != "find" && t != "look for" ||
                Regex("^(any )?(trade )?(opportunities|setups)( now| today| right now)?$").matches(t)
            ) return scanText(c)
            if (Regex("^(how are|how is) (my )?(setups|signals|journal|trading)( doing)?$|^(trading |setup )?(stats|journal)$").matches(t)) {
                return Journal.statsText(c)
            }

            // ---- lessons that Orion keeps and uses in later analysis
            val les = Regex("^(trading )?lesson:? (.+)$").find(t)
            if (les != null) {
                MemoryStore.add(c, "decision", "Trading lesson: " + les.groupValues[2].trim())
                return "Saved. I will use that lesson when I analyse charts."
            }

            // ---- limits and mode
            val mo = Regex("^set max (order|size) (to )?\\$?(\\d+)( dollars| usdt)?$").find(t)
            if (mo != null) return setLimit(c, mo.groupValues[3].toFloat(), null)
            val mr = Regex("^set max risk (to )?(\\d+(\\.\\d+)?) ?(percent|%)?$").find(t)
            if (mr != null) return setLimit(c, null, mr.groupValues[2].toFloat())
            if (Regex("^(use|switch to|go) (real|live) (money|trading|account|mode)$").matches(t)) {
                if (!confirm("Switch to REAL money trading? Orders will use your real Bybit balance. Every order will still ask you first.")) return "Staying in the current mode."
                Prefs.setBybitTestnet(c, false)
                return "Real money mode is on. I will still confirm every order."
            }
            if (Regex("^(use|switch to|go) (test|testnet|practice|demo)( money| trading| account| mode)?$").matches(t)) {
                Prefs.setBybitTestnet(c, true)
                return "Practice mode is on. No real money is used."
            }

            // ---- orders
            val od = Regex("^(buy|long|sell|short)( for me)? \\$?(\\d+(\\.\\d+)?) ?(usdt|usd|dollars|dollar)? ?(worth )?(of |on |in )?([a-z0-9]+)(.*)$").find(t)
            if (od != null) {
                val sym = Bybit.symbolOf(od.groupValues[8])
                if (sym != null) {
                    val side = if (od.groupValues[1] == "buy" || od.groupValues[1] == "long") "long" else "short"
                    val rest = od.groupValues[9]
                    val stopM = Regex("(stop( loss)?|sl)( at| is)? \\$?(\\d+(\\.\\d+)?)").find(rest)
                    val tpM = Regex("(target|tp|take profit)( at| is)? \\$?(\\d+(\\.\\d+)?)").find(rest)
                    if (stopM == null) {
                        return "For safety I need a stop loss. Say it like: " + od.groupValues[1] + " " + od.groupValues[3] + " usdt of " +
                            od.groupValues[8] + " stop 3200 target 3600."
                    }
                    return placeOrder(
                        c, side, num(od.groupValues[3]), sym, num(stopM.groupValues[4]),
                        if (tpM != null) num(tpM.groupValues[3]) else null, confirm
                    )
                }
            }

            // ---- closing and the kill switch
            if (Regex("^(close|exit|flatten) (all )?(positions|trades|everything|all)$").matches(t)) {
                needKeys(c)?.let { return it }
                if (!confirm("Close ALL open positions at market price now? Mode: " + Bybit.modeText(c) + ".")) return "Okay, leaving them open."
                return Bybit.closeAll(c)
            }
            if (Regex("^(kill switch|panic|emergency stop)$").matches(t)) {
                needKeys(c)?.let { return it }
                if (!confirm("KILL SWITCH: cancel all orders and close all positions now? Mode: " + Bybit.modeText(c) + ".")) return "Okay, nothing changed."
                val a = Bybit.cancelAll(c)
                return a + " " + Bybit.closeAll(c)
            }
            if (Regex("^cancel (all )?orders$").matches(t)) {
                needKeys(c)?.let { return it }
                if (!confirm("Cancel all open orders?")) return "Okay, nothing changed."
                return Bybit.cancelAll(c)
            }

            // ---- background watcher and alerts
            if (Regex("^(start|begin|turn on) (watching|trade watch|market watch|the watcher|watching the market)( the market)?$|^watch the market$").matches(t)) {
                WatchService.start(c)
                return "Market watch is on. I will check your watchlist about every " + Prefs.watchMinutes(c) +
                    " minutes, even with the screen off, and send you a notification when I find a setup. For best results, set Orion to unrestricted battery use."
            }
            if (Regex("^(stop|end|turn off) (watching|trade watch|market watch|the watcher)( the market)?$").matches(t)) {
                WatchService.stop(c)
                return "Market watch is off."
            }
            val wl = Regex("^(set )?watchlist( to| is)? (.+)$").find(t)
            if (wl != null) {
                val list = wl.groupValues[3].split(Regex("[ ,]+|\\band\\b")).mapNotNull { Bybit.symbolOf(it) }.distinct()
                if (list.isEmpty()) return "I did not recognise any coins. Say: set watchlist to btc eth sol."
                Prefs.setWatchlist(c, list.joinToString(","))
                return "Watchlist is now " + list.joinToString(", ") { it.removeSuffix("USDT") } + "."
            }
            if (Regex("^(what is |show |read )?(my )?watchlist$").matches(t)) {
                return "Your watchlist: " + Prefs.watchlist(c).split(",").joinToString(", ") { it.removeSuffix("USDT") } + "."
            }
            val al = Regex("^alert me when ([a-z0-9]+) (?:is |goes |drops |falls |rises |crosses |hits |reaches )*(above|below|over|under|to) \\$?(\\d+(\\.\\d+)?)$").find(t)
            if (al != null) {
                val sym = Bybit.symbolOf(al.groupValues[1]) ?: return "I don't know that coin."
                val dir = if (al.groupValues[2] == "below" || al.groupValues[2] == "under") "below" else "above"
                val arr = alertsArr(c)
                arr.put(JSONObject().put("symbol", sym).put("dir", dir).put("price", num(al.groupValues[3])))
                Prefs.setAlerts(c, arr.toString())
                if (!Prefs.watchOn(c)) WatchService.start(c)
                return "Alert set: " + sym.removeSuffix("USDT") + " " + dir + " " + al.groupValues[3] + ". Market watch is on so I can tell you even with the screen off."
            }
            if (Regex("^(list|show|what are) (my )?alerts$").matches(t)) {
                val arr = alertsArr(c)
                if (arr.length() == 0) return "You have no price alerts."
                val sb = StringBuilder("Your alerts: ")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    sb.append(o.optString("symbol").removeSuffix("USDT")).append(" ").append(o.optString("dir")).append(" ").append(o.optDouble("price")).append(". ")
                }
                return sb.toString().trim()
            }
            if (Regex("^(clear|delete|remove) (all )?(my )?alerts$").matches(t)) {
                Prefs.setAlerts(c, "[]")
                return "All alerts cleared."
            }
        } catch (e: Exception) {
            return fail(e)
        }
        return null
    }
}
