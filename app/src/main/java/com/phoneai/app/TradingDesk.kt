package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/**
 * Android glue for the trading desk: persists strategies, the paper account, the evidence ledger, daily counters and
 * the kill switch, and exposes the agent tools. Every real order still needs the user's explicit approval in
 * Trading.placeOrder; this object only adds the risk gate and the research and practice tools.
 */
internal object TradingDesk {
    private fun sp(c: Context) = c.getSharedPreferences("trading_desk", Context.MODE_PRIVATE)
    private val defaultFx = listOf("EURUSD", "GBPUSD", "USDJPY", "XAUUSD")

    // ---- kill switch and counters
    fun killSwitch(c: Context) = sp(c).getBoolean("kill", false)
    fun setKill(c: Context, on: Boolean) = sp(c).edit().putBoolean("kill", on).apply()

    private fun dayKey(now: Long) = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate().toString()

    fun tradesToday(c: Context, now: Long = System.currentTimeMillis()): Int =
        if (sp(c).getString("day", "") == dayKey(now)) sp(c).getInt("trades_today", 0) else 0

    fun recordTrade(c: Context, now: Long = System.currentTimeMillis()) {
        val n = tradesToday(c, now) + 1
        sp(c).edit().putString("day", dayKey(now)).putInt("trades_today", n).apply()
    }

    private fun dayStartEquity(c: Context, equity: Double, now: Long): Double {
        val p = sp(c)
        if (p.getString("eq_day", "") != dayKey(now)) p.edit().putString("eq_day", dayKey(now)).putFloat("eq_start", equity.toFloat()).apply()
        return p.getFloat("eq_start", equity.toFloat()).toDouble()
    }

    fun recordOutcome(c: Context, rMultiple: Double, now: Long = System.currentTimeMillis()) {
        val p = sp(c)
        if (rMultiple < 0) p.edit().putInt("loss_streak", p.getInt("loss_streak", 0) + 1).putLong("last_loss", now).apply()
        else p.edit().putInt("loss_streak", 0).apply()
    }

    fun limits(c: Context) = TradingRisk.Limits(maxOrderUsd = Prefs.maxOrderUsd(c).toDouble(), maxRiskPct = Prefs.maxRiskPct(c).toDouble())

    // ---- calendar cache
    @Volatile private var cal: Pair<Long, List<MarketCalendar.Event>> = 0L to emptyList()
    fun events(now: Long = System.currentTimeMillis()): List<MarketCalendar.Event> {
        if (now - cal.first > 3_600_000L || cal.second.isEmpty() && now - cal.first > 300_000L) {
            val fresh = MarketCalendar.fetchThisWeek()
            if (fresh.isNotEmpty() || cal.second.isEmpty()) cal = now to fresh
        }
        return cal.second
    }

    /** The gate every real order passes before the approval prompt. Returns null when allowed, else the reason text. */
    fun gateOrder(c: Context, side: String, symbol: String, usd: Double, entry: Double, stop: Double, target: Double?, equity: Double): String? {
        val now = System.currentTimeMillis()
        val blackout = MarketCalendar.inBlackout(events(now), now, MarketCalendar.currenciesFor(symbol))
        val acct = TradingRisk.Account(
            equity = equity, dayStartEquity = dayStartEquity(c, equity, now), openPositions = 0,
            tradesToday = tradesToday(c, now), consecutiveLosses = sp(c).getInt("loss_streak", 0), lastLossAtMillis = sp(c).getLong("last_loss", 0L)
        )
        val v = TradingRisk.evaluate(TradingRisk.Order(symbol, side, usd, entry, stop, target), acct, limits(c), now, killSwitch(c), blackout)
        return if (v.allowed) null else "Blocked by the risk gate: " + v.reasons.joinToString("; ") + "."
    }

    // ---- strategies
    fun strategies(c: Context): List<StrategySpec> = try {
        val a = JSONArray(sp(c).getString("strategies", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            StrategySpec(o.getString("name"), strs(o.optJSONArray("instruments")), strs(o.optJSONArray("timeframes")), strs(o.optJSONArray("setups")),
                o.optDouble("min_rr", 1.5), o.optDouble("risk_pct", 1.0), o.optBoolean("news_filter", true), o.optString("notes"))
        }
    } catch (_: Exception) { emptyList() }

    private fun strs(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }

    private fun saveStrategies(c: Context, l: List<StrategySpec>) {
        val a = JSONArray()
        l.forEach { s ->
            a.put(JSONObject().put("name", s.name).put("instruments", JSONArray(s.instruments)).put("timeframes", JSONArray(s.timeframes))
                .put("setups", JSONArray(s.setupTypes)).put("min_rr", s.minRewardToRisk).put("risk_pct", s.riskPct)
                .put("news_filter", s.newsFilter).put("notes", s.notes))
        }
        sp(c).edit().putString("strategies", a.toString()).apply()
    }

    // ---- evidence ledger: R multiples per setup type
    private fun ledger(c: Context): JSONArray = try { JSONArray(sp(c).getString("ledger", "[]")) } catch (_: Exception) { JSONArray() }

    fun recordResult(c: Context, strategy: String, setup: String, r: Double) {
        val a = ledger(c)
        a.put(JSONObject().put("s", strategy).put("t", setup).put("r", r).put("at", System.currentTimeMillis()))
        val keep = JSONArray(); for (i in maxOf(0, a.length() - 500) until a.length()) keep.put(a.get(i))
        sp(c).edit().putString("ledger", keep.toString()).apply()
        recordOutcome(c, r)
    }

    private fun resultsFor(c: Context, key: (JSONObject) -> String): Map<String, List<Double>> {
        val a = ledger(c)
        return (0 until a.length()).map { a.getJSONObject(it) }.groupBy(key).mapValues { e -> e.value.map { it.optDouble("r") } }
    }

    private fun winRateOf(c: Context): (String) -> Double? {
        val m = resultsFor(c) { it.optString("t") }
        return { t -> m[t]?.takeIf { it.size >= 5 }?.let { r -> r.count { it > 0 }.toDouble() / r.size } }
    }

    fun evidenceText(c: Context): String {
        val by = resultsFor(c) { it.optString("s").ifBlank { "unassigned" } }
        if (by.isEmpty()) return "No recorded results yet. Record wins and losses (in R) and I will tell you what is working once there are enough trades to mean something."
        return by.entries.joinToString("\n") { (k, v) -> "$k: " + TradingStats.text(TradingStats.summarize(v)) }
    }

    // ---- paper account
    private fun paper(c: Context): PaperBroker.State = try {
        val o = JSONObject(sp(c).getString("paper", "") ?: "")
        PaperBroker.State(
            o.getDouble("start"), o.getDouble("bal"),
            o.getJSONArray("open").let { a -> (0 until a.length()).map { val p = a.getJSONObject(it)
                PaperBroker.Position(p.getInt("id"), p.getString("sym"), p.getBoolean("long"), p.getDouble("usd"), p.getDouble("entry"), p.getDouble("stop"),
                    if (p.has("target")) p.getDouble("target") else null, p.optString("setup"), p.getLong("at")) } },
            o.getJSONArray("closed").let { a -> (0 until a.length()).map { val p = a.getJSONObject(it)
                PaperBroker.Closed(p.getInt("id"), p.getString("sym"), p.getBoolean("long"), p.optString("setup"), p.getDouble("entry"), p.getDouble("exit"),
                    p.getString("why"), p.getDouble("pnl"), p.getDouble("r"), p.getLong("at")) } },
            o.getInt("next")
        )
    } catch (_: Exception) { PaperBroker.start(10_000.0) }

    private fun savePaper(c: Context, s: PaperBroker.State) {
        val open = JSONArray(); s.open.forEach { open.put(JSONObject().put("id", it.id).put("sym", it.symbol).put("long", it.long).put("usd", it.usd)
            .put("entry", it.entry).put("stop", it.stop).apply { it.target?.let { t -> put("target", t) } }.put("setup", it.setup).put("at", it.openedAt)) }
        val closed = JSONArray(); s.closed.takeLast(300).forEach { closed.put(JSONObject().put("id", it.id).put("sym", it.symbol).put("long", it.long).put("setup", it.setup)
            .put("entry", it.entry).put("exit", it.exit).put("why", it.reason).put("pnl", it.pnlUsd).put("r", it.rMultiple).put("at", it.closedAt)) }
        sp(c).edit().putString("paper", JSONObject().put("start", s.startBalance).put("bal", s.balance).put("open", open).put("closed", closed).put("next", s.nextId).toString()).apply()
    }

    private fun lastPrice(c: Context, sym: String): Double? = try {
        if (FxData.pairOf(sym) != null) FxData.candles(FxData.pairOf(sym)!!, "15m").lastOrNull()?.c else Bybit.price(c, sym)
    } catch (_: Exception) { null }

    /** Marks open paper trades against recent candles so stops and targets resolve even while the user is away. */
    fun updatePaper(c: Context): String {
        var s = paper(c)
        val before = s.closed.size
        for (sym in s.open.map { it.symbol }.distinct()) {
            val cs = try { FxData.pairOf(sym)?.let { FxData.candles(it, "15m") } ?: Bybit.candles(c, Bybit.symbolOf(sym) ?: sym, "15", 100) } catch (_: Exception) { emptyList() }
            val opened = s.open.filter { it.symbol == sym }.minOfOrNull { it.openedAt } ?: continue
            cs.filter { it.t >= opened }.forEach { k -> s = PaperBroker.onCandle(s, sym, k.h, k.l, k.t) }
        }
        s.closed.drop(before).forEach { recordResult(c, "paper", it.setup.ifBlank { "manual" }, it.rMultiple) }
        savePaper(c, s)
        return "Paper account: balance ${"%.2f".format(Locale.US, s.balance)}, ${s.open.size} open, ${s.closed.size} closed. " +
            (if (s.open.isNotEmpty()) "Open: " + s.open.joinToString("; ") { "#${it.id} ${if (it.long) "long" else "short"} ${it.symbol} @ ${Ta.price(it.entry)} stop ${Ta.price(it.stop)}" } + ". " else "") +
            TradingStats.text(TradingStats.summarize(s.closed.map { it.rMultiple }))
    }

    // ---- setup scan across crypto and forex
    fun scan(c: Context, symbols: List<String>): String {
        val now = System.currentTimeMillis()
        val ev = events(now)
        val hour = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).hour
        val setups = ArrayList<Setup>()
        val failed = ArrayList<String>()
        for (raw in symbols) {
            val fx = FxData.pairOf(raw)
            val sym = fx ?: (Bybit.symbolOf(raw) ?: raw.uppercase(Locale.US))
            try {
                val h1 = if (fx != null) FxData.candles(fx, "1h") else Bybit.candles(c, sym, "60", 200)
                val h4 = if (fx != null) FxData.candles(fx, "4h") else Bybit.candles(c, sym, "240", 200)
                if (h1.size < 40 || h4.size < 40) { failed += sym; continue }
                val htf = Ta.trend(h4.dropLast(1))
                setups.addAll(Ta.scan(sym, "1h", h1.dropLast(1), htf)); setups.addAll(Ta.scan(sym, "4h", h4.dropLast(1), htf))
            } catch (_: Exception) { failed += sym }
        }
        val strategies = strategies(c).ifEmpty { listOf(StrategySpec("default")) }
        val ranked = SetupMatcher.rank(setups, strategies, hour, { MarketCalendar.inBlackout(ev, now, MarketCalendar.currenciesFor(it), 60, 15) }, winRateOf(c))
        val sb = StringBuilder()
        sb.append(if (ranked.isEmpty()) "No setups match your rules right now." else ranked.take(5).joinToString("\n") { SetupMatcher.text(it) })
        if (failed.isNotEmpty()) sb.append("\nCould not read data for: ").append(failed.joinToString(", ")).append(".")
        sb.append("\n").append(MarketSessions.describe(hour))
        return sb.toString()
    }

    fun watchSymbols(c: Context): List<String> =
        (Prefs.watchlist(c).split(",").map { it.trim() }.filter { it.isNotEmpty() } + defaultFx.filter { false }).distinct()

    // ---- tool entry point
    fun run(c: Context, tool: String, a: JSONObject): String = try {
        val now = System.currentTimeMillis()
        val hour = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).hour
        when (tool) {
            "trading_calendar" -> {
                val sym = a.optString("symbol")
                val cur = if (sym.isBlank()) null else MarketCalendar.currenciesFor(sym)
                val l = MarketCalendar.upcoming(events(now), now, a.optInt("hours", 48).coerceIn(1, 168), MarketCalendar.Impact.MEDIUM, cur)
                MarketCalendar.text(l, now)
            }
            "trading_sessions" -> MarketSessions.describe(hour) + a.optString("symbol").takeIf { it.isNotBlank() }?.let {
                " " + it.uppercase(Locale.US) + (if (MarketSessions.inPreferredSession(it, hour)) " is in its usual trading hours." else " is outside its usual trading hours.")
            }.orEmpty()
            "trade_risk_check" -> {
                val eq = a.optDouble("equity", 0.0).takeIf { it > 0 } ?: try { Bybit.equity(c) } catch (_: Exception) { 0.0 }
                if (eq <= 0) "Give me your account equity (equity field) so I can size the risk." else {
                    val sym = a.optString("symbol").uppercase(Locale.US)
                    val acct = TradingRisk.Account(eq, dayStartEquity(c, eq, now), a.optInt("open_positions", 0), tradesToday(c, now), sp(c).getInt("loss_streak", 0), sp(c).getLong("last_loss", 0L))
                    val ord = TradingRisk.Order(sym, a.optString("side", "buy"), a.optDouble("usd", 0.0), a.optDouble("entry", 0.0), a.optDouble("stop", 0.0), if (a.has("target")) a.optDouble("target") else null)
                    val v = TradingRisk.evaluate(ord, acct, limits(c), now, killSwitch(c), MarketCalendar.inBlackout(events(now), now, MarketCalendar.currenciesFor(sym)))
                    (if (v.allowed) "Allowed. " else "Blocked: " + v.reasons.joinToString("; ") + ". ") +
                        "Risk ${"%.2f".format(Locale.US, v.riskUsd)} USD (${"%.2f".format(Locale.US, v.riskPct)}%), R:R ${"%.2f".format(Locale.US, v.rewardToRisk)}, suggested size ${"%.0f".format(Locale.US, v.suggestedUsd)} USD."
                }
            }
            "setup_scan" -> {
                val l = a.optJSONArray("symbols")?.let { strs(it) }.orEmpty().ifEmpty { watchSymbols(c) + defaultFx }
                scan(c, l.take(12))
            }
            "strategy_save" -> {
                val name = a.optString("name").trim().take(40)
                if (name.isEmpty()) "A strategy needs a name." else {
                    val s = StrategySpec(name, strs(a.optJSONArray("instruments")).map { (FxData.pairOf(it) ?: it).uppercase(Locale.US) }, strs(a.optJSONArray("timeframes")),
                        strs(a.optJSONArray("setup_types")), a.optDouble("min_rr", 1.5).coerceIn(0.5, 10.0), a.optDouble("risk_pct", 1.0).coerceIn(0.1, 5.0), a.optBoolean("news_filter", true), a.optString("notes").take(500))
                    saveStrategies(c, strategies(c).filterNot { it.name.equals(name, true) } + s)
                    "Saved strategy '$name'. I will match setups against it and track its results."
                }
            }
            "strategy_list" -> strategies(c).let { l ->
                if (l.isEmpty()) "No strategies saved yet. Tell me your rules or upload a chart and I will write them down."
                else l.joinToString("\n") { "${it.name}: ${it.instruments.ifEmpty { listOf("any") }.joinToString("/")} ${it.timeframes.ifEmpty { listOf("any tf") }.joinToString("/")} min R:R ${it.minRewardToRisk} risk ${it.riskPct}%. ${it.notes}" }
            } + "\n" + evidenceText(c)
            "strategy_record_result" -> {
                val r = a.optDouble("r_multiple", Double.NaN)
                if (r.isNaN()) "Give the result in R (for example 2 for a 2R win, -1 for a full loss)." else {
                    recordResult(c, a.optString("strategy").ifBlank { "unassigned" }, a.optString("setup_type").ifBlank { "manual" }, r)
                    "Recorded ${"%.2f".format(Locale.US, r)}R. " + evidenceText(c)
                }
            }
            "paper_trade" -> when (a.optString("action", "status")) {
                "open" -> {
                    val sym = (FxData.pairOf(a.optString("symbol")) ?: Bybit.symbolOf(a.optString("symbol")) ?: a.optString("symbol")).uppercase(Locale.US)
                    val long = a.optString("side", "buy").lowercase(Locale.US) in setOf("buy", "long")
                    val entry = a.optDouble("entry", 0.0).takeIf { it > 0 } ?: lastPrice(c, sym) ?: 0.0
                    val s = paper(c)
                    val usd = a.optDouble("usd", 0.0)
                    val v = if (entry > 0) TradingRisk.evaluate(TradingRisk.Order(sym, if (long) "buy" else "sell", usd, entry, a.optDouble("stop", 0.0), if (a.has("target")) a.optDouble("target") else null),
                        TradingRisk.Account(s.balance, s.startBalance, s.open.size, 0), limits(c).copy(maxOrderUsd = s.balance), now) else null
                    when {
                        entry <= 0 -> "I could not read a price for $sym; give an entry."
                        v != null && !v.allowed -> "Paper trade blocked by the same risk rules: " + v.reasons.joinToString("; ") + "."
                        else -> {
                            val n = PaperBroker.open(s, sym, long, usd, entry, a.optDouble("stop"), if (a.has("target")) a.optDouble("target") else null, a.optString("setup_type"), now)
                            savePaper(c, n); "Opened paper ${if (long) "long" else "short"} $sym at ${Ta.price(entry)}. No real money involved."
                        }
                    }
                }
                "close" -> {
                    val s = paper(c); val p = s.open.firstOrNull { it.id == a.optInt("id", -1) }
                    if (p == null) "No open paper trade with that id." else {
                        val px = a.optDouble("price", 0.0).takeIf { it > 0 } ?: lastPrice(c, p.symbol) ?: p.entry
                        val n = PaperBroker.closeAt(s, p.id, px, now); savePaper(c, n)
                        n.closed.lastOrNull()?.let { recordResult(c, "paper", it.setup.ifBlank { "manual" }, it.rMultiple) }
                        "Closed paper #${p.id} at ${Ta.price(px)}."
                    }
                }
                "reset" -> { savePaper(c, PaperBroker.start(a.optDouble("balance", 10_000.0).coerceAtLeast(100.0))); "Paper account reset." }
                else -> updatePaper(c)
            }
            "trading_kill_switch" -> { setKill(c, a.optBoolean("on", true)); "Kill switch is now ${if (killSwitch(c)) "ON: no new orders will pass the risk gate" else "OFF"}." }
            else -> "Unknown trading tool $tool."
        }
    } catch (e: Exception) { "Trading tool failed: " + (e.message ?: e.javaClass.simpleName) }
}
