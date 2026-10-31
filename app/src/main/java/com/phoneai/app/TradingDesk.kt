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

    private fun dayStartEquity(c: Context, equity: Double, now: Long, account: String = "bybit"): Double {
        val p = sp(c)
        if (p.getString("eq_day_$account", "") != dayKey(now)) p.edit().putString("eq_day_$account", dayKey(now)).putFloat("eq_start_$account", equity.toFloat()).apply()
        return p.getFloat("eq_start_$account", equity.toFloat()).toDouble()
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
            val fresh = CalendarFeed.fetchThisWeek()
            if (fresh.isNotEmpty() || cal.second.isEmpty()) cal = now to fresh
        }
        return cal.second
    }

    /** The gate every real order passes before the approval prompt. Returns null when allowed, else the reason text. */
    fun gateOrder(c: Context, side: String, symbol: String, usd: Double, entry: Double, stop: Double, target: Double?, equity: Double, account: String = "bybit"): String? {
        val now = System.currentTimeMillis()
        val blackout = MarketCalendar.inBlackout(events(now), now, MarketCalendar.currenciesFor(symbol))
        val acct = TradingRisk.Account(
            equity = equity, dayStartEquity = dayStartEquity(c, equity, now, account), openPositions = 0,
            tradesToday = tradesToday(c, now), consecutiveLosses = sp(c).getInt("loss_streak", 0), lastLossAtMillis = sp(c).getLong("last_loss", 0L)
        )
        val v = TradingRisk.evaluate(TradingRisk.Order(symbol, side, usd, entry, stop, target), acct, limits(c), now, killSwitch(c), blackout)
        return if (v.allowed) null else "Blocked by the risk gate: " + v.reasons.joinToString("; ") + "."
    }

    // ---- strategies
    fun strategies(c: Context): List<StrategySpec> = readSpecs(c, "strategies")
    fun pending(c: Context): List<StrategySpec> = readSpecs(c, "pending")

    private fun readSpecs(c: Context, key: String): List<StrategySpec> = try {
        val a = JSONArray(sp(c).getString(key, "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            StrategySpec(o.getString("name"), strs(o.optJSONArray("instruments")), strs(o.optJSONArray("timeframes")), strs(o.optJSONArray("setups")),
                o.optDouble("min_rr", 1.5), o.optDouble("risk_pct", 1.0), o.optBoolean("news_filter", true), o.optString("notes"))
        }
    } catch (_: Exception) { emptyList() }

    private fun strs(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }

    private fun saveStrategies(c: Context, l: List<StrategySpec>) = writeSpecs(c, "strategies", l)

    private fun writeSpecs(c: Context, key: String, l: List<StrategySpec>) {
        val a = JSONArray()
        l.forEach { s ->
            a.put(JSONObject().put("name", s.name).put("instruments", JSONArray(s.instruments)).put("timeframes", JSONArray(s.timeframes))
                .put("setups", JSONArray(s.setupTypes)).put("min_rr", s.minRewardToRisk).put("risk_pct", s.riskPct)
                .put("news_filter", s.newsFilter).put("notes", s.notes))
        }
        sp(c).edit().putString(key, a.toString()).apply()
    }

    // ---- playbook: strategies the user accepted, proposals waiting for a decision
    fun addPending(c: Context, s: StrategySpec) = writeSpecs(c, "pending", (pending(c).filterNot { it.name.equals(s.name, true) } + s).takeLast(10))
    fun dismissPending(c: Context, name: String) = writeSpecs(c, "pending", pending(c).filterNot { it.name.equals(name, true) })
    fun accept(c: Context, s: StrategySpec) { saveStrategies(c, strategies(c).filterNot { it.name.equals(s.name, true) } + s); dismissPending(c, s.name) }
    fun acceptPending(c: Context, name: String): Boolean = pending(c).firstOrNull { it.name.equals(name, true) }?.let { accept(c, it); true } ?: false
    fun deleteStrategy(c: Context, name: String) = saveStrategies(c, strategies(c).filterNot { it.name.equals(name, true) })

    /** Plain-language results for one strategy from the evidence ledger. */
    fun evidenceFor(c: Context, strategy: String): String {
        val rs = resultsFor(c) { it.optString("s") }[strategy].orEmpty()
        return if (rs.isEmpty()) "no recorded results yet" else TradingStats.text(TradingStats.summarize(rs))
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
    class ScanResult(val matches: List<SetupMatcher.Match>, val failed: List<String>, val hour: Int)

    fun match(c: Context, symbols: List<String>, blackoutBeforeMin: Int = 60): ScanResult {
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
                Journal.evaluate(c, sym, h1) // settle earlier setups so win rates keep learning
                val htf = Ta.trend(h4.dropLast(1))
                setups.addAll(Ta.scan(sym, "1h", h1.dropLast(1), htf)); setups.addAll(Ta.scan(sym, "4h", h4.dropLast(1), htf))
            } catch (_: Exception) { failed += sym }
        }
        val strategies = strategies(c).ifEmpty { listOf(StrategySpec("default")) }
        val ranked = SetupMatcher.rank(setups, strategies, hour, { MarketCalendar.inBlackout(ev, now, MarketCalendar.currenciesFor(it), blackoutBeforeMin, 15) }, winRateOf(c))
        return ScanResult(ranked, failed, hour)
    }

    fun scan(c: Context, symbols: List<String>): String {
        val r = match(c, symbols)
        val sb = StringBuilder()
        sb.append(if (r.matches.isEmpty()) "No setups match your rules right now." else r.matches.take(5).joinToString("\n") { SetupMatcher.text(it) })
        if (r.failed.isNotEmpty()) sb.append("\nCould not read data for: ").append(r.failed.joinToString(", ")).append(".")
        sb.append("\n").append(MarketSessions.describe(r.hour))
        return sb.toString()
    }

    /** Symbols the automation should follow: whatever the playbook names, else the crypto watchlist. */
    fun followSymbols(c: Context): List<String> =
        (strategies(c).flatMap { it.instruments }.ifEmpty { watchSymbols(c) }).distinct().take(12)

    /** One automated pass for the background watcher: settle practice trades, warn before big news, alert matches, morning brief. */
    fun watchCycle(c: Context, notifyRaw: (Int, String, String) -> Unit) {
        if (!Prefs.deskAuto(c)) return
        val notify: (Int, String, String) -> Unit = { id, title, text -> logActivity(c, title, text); notifyRaw(id, title, text) }
        val now = System.currentTimeMillis()
        val hour = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).hour
        val ev = events(now)
        val follow = followSymbols(c)
        // practice trades resolve while the user is away
        if (paper(c).open.isNotEmpty()) {
            val before = paper(c).closed.size
            updatePaper(c)
            val after = paper(c)
            after.closed.drop(before).forEach { notify(4100 + it.id % 50, "Practice trade closed", "${it.symbol} ${it.reason}: ${"%+.2f".format(Locale.US, it.rMultiple)}R") }
        }
        // heads-up before high-impact news on currencies the user follows
        val cur = follow.flatMap { MarketCalendar.currenciesFor(it) }.toSet()
        val seen = sp(c).getString("news_seen", "").orEmpty().split("\n").filter { it.isNotBlank() }.toMutableList()
        MarketCalendar.upcoming(ev, now, 1, MarketCalendar.Impact.HIGH, cur).filter { it.key !in seen }.take(2).forEachIndexed { i, e ->
            seen += e.key
            notify(4200 + i, "High-impact news soon", MarketCalendar.text(listOf(e), now) + ". New orders on ${e.currency} pairs are paused around it.")
        }
        sp(c).edit().putString("news_seen", seen.takeLast(50).joinToString("\n")).apply()
        // setups that fit the playbook
        var sent = 0
        for (m in match(c, follow).matches) {
            if (sent >= 2 || m.score < 55) break
            if (Journal.add(c, m.setup)) { notify(4300 + sent, "Orion: setup for your playbook", SetupMatcher.text(m)); sent++ }
        }
        // open positions, newly closed trades, habits, crowded funding
        try { monitorPositions(c, notify) } catch (_: Exception) { }
        try {
            if (Bybit.hasKeys(c) && now - sp(c).getLong("last_sync", 0L) > 30 * 60_000L) {
                val fresh = syncBybit(c)
                fresh.take(3).forEachIndexed { i, t -> notify(4600 + i, "Trade closed: ${t.symbol.removeSuffix("USDT")}", "${t.side} ${"%+.2f".format(Locale.US, t.pnl ?: 0.0)} USD. Logged in your journal.") }
                if (fresh.isNotEmpty() && sp(c).getString("habit_day", "") != dayKey(now)) {
                    val rep = habits(c)
                    rep.findings.firstOrNull()?.let { sp(c).edit().putString("habit_day", dayKey(now)).apply(); notify(4610, "Habit check", it.text) }
                }
            }
        } catch (_: Exception) { }
        try {
            follow.filter { FxData.pairOf(it) == null }.take(3).forEach { sym ->
                val r = derivatives(c, sym) ?: return@forEach
                val k = "fund_$sym"
                if (r.extremeFunding && now - sp(c).getLong(k, 0L) > 12 * 3_600_000L) { sp(c).edit().putLong(k, now).apply(); notify(4620, "Crowded funding on ${sym.removeSuffix("USDT")}", r.text) }
            }
        } catch (_: Exception) { }
        try { ChainDesk.watchCycle(c, notify) } catch (_: Exception) { }
        // one morning brief a day
        val day = dayKey(now)
        if (hour >= 6 && sp(c).getString("brief_day", "") != day) {
            sp(c).edit().putString("brief_day", day).apply()
            val today = MarketCalendar.upcoming(ev, now, 16, MarketCalendar.Impact.HIGH, null).take(4)
            notify(4400, "Orion morning brief", MarketSessions.describe(hour) + "\n" + MarketCalendar.text(today, now) +
                "\nPlaybook: ${strategies(c).size} strateg${if (strategies(c).size == 1) "y" else "ies"}, watching ${follow.joinToString(", ").ifBlank { "nothing yet" }}.")
        }
    }

    // ---- simulation, forecast and conditions
    private fun tfCode(tf: String, bybit: Boolean) = when (tf) { "15m" -> if (bybit) "15" else "15m"; "4h" -> if (bybit) "240" else "4h"; "1d" -> if (bybit) "D" else "1d"; else -> if (bybit) "60" else "1h" }

    private fun candlesFor(c: Context, raw: String, tf: String): Pair<String, List<Candle>> {
        val fx = FxData.pairOf(raw)
        return if (fx != null) fx to FxData.candles(fx, tf)
        else (Bybit.symbolOf(raw) ?: raw.uppercase(Locale.US)).let { it to Bybit.candles(c, it, tfCode(tf, true), 1000) }
    }

    class Simulation(val text: String, val curve: List<Double>)

    fun backtest(c: Context, strategyName: String?, symbol: String, tf: String = "1h"): Simulation {
        val (sym, cs) = candlesFor(c, symbol, tf)
        if (cs.size < 200) return Simulation("Not enough history for $sym $tf yet (${cs.size} candles).", emptyList())
        val st = strategies(c).firstOrNull { strategyName != null && it.name.equals(strategyName, true) }
            ?: strategies(c).firstOrNull { it.instruments.isEmpty() || it.instruments.any { i -> i.equals(sym, true) } }
            ?: StrategySpec("default rules")
        val r = StrategyBacktest.run(sym, tf, cs.dropLast(1), st)
        return Simulation("Strategy '${st.name}'. " + r.text(), r.curve)
    }

    /** One lean for a symbol from technicals, the playbook, its simulated record and news or calendar tone. */
    fun forecast(c: Context, symbol: String): String {
        val now = System.currentTimeMillis()
        val (sym, h1) = candlesFor(c, symbol, "1h")
        val (_, h4) = candlesFor(c, symbol, "4h")
        val (_, d1) = candlesFor(c, symbol, "1d")
        val outlook = MarketOutlook.compute(sym, h1, h4, d1) ?: return "Not enough data to read $sym right now."
        val live = match(c, listOf(sym)).matches.firstOrNull()
        val sim = live?.let { m -> backtest(c, m.strategy, sym, m.setup.tf).let { s -> s } }
        val strat = strategies(c).firstOrNull { it.name == live?.strategy } ?: StrategySpec("default rules")
        val rs = if (live != null) StrategyBacktest.run(sym, live.setup.tf, (if (live.setup.tf == "4h") h4 else h1).dropLast(1), strat).rs else emptyList()
        val summary = TradingStats.summarize(rs)
        val fx = FxData.pairOf(sym) != null
        val deriv = if (fx) null else derivatives(c, sym)
        val tilt = if (fx) CurrencyConditions.pairTilt(events(now), now, sym)
            else HeadlineSentiment.score(NewsDesk.cryptoHeadlines(sym)).tilt * (if (deriv != null) 0.6 else 1.0) + (deriv?.tilt ?: 0.0) * 0.4
        val f = MarketForecast.forecast(MarketForecast.Input(sym, outlook.bias, outlook.confidence,
            when (live?.setup?.side) { "long" -> 1; "short" -> -1; else -> 0 }, if (rs.isEmpty()) null else summary.expectancyR, rs.size, tilt))
        return f.text(sym) + "\n" + outlook.text + (if (deriv != null) "\n" + deriv.text else "") + (if (sim != null) "\n" + sim.text else "")
    }

    /** What the economic calendar and news say about a symbol (or the playbook's pairs when none is named). */
    fun conditions(c: Context, symbol: String?): String {
        val now = System.currentTimeMillis()
        val ev = events(now)
        val targets = if (symbol.isNullOrBlank()) followSymbols(c).take(4) else listOf(symbol)
        return targets.joinToString("\n\n") { raw ->
            val fx = FxData.pairOf(raw)
            val sym = fx ?: (Bybit.symbolOf(raw) ?: raw.uppercase(Locale.US))
            val heads = NewsDesk.headlinesFor(sym)
            val tone = HeadlineSentiment.score(heads)
            (if (fx != null) CurrencyConditions.pairText(ev, now, sym) else "$sym: macro calendar (USD) " + CurrencyConditions.assess(ev, now, "USD").text(now)) +
                "\nNews tone: ${tone.label} (${tone.bullish} positive, ${tone.bearish} negative of ${tone.total})." +
                (if (heads.isNotEmpty()) "\n" + heads.take(4).joinToString("\n") { "- $it" } else "")
        }
    }

    fun watchSymbols(c: Context): List<String> =
        (Prefs.watchlist(c).split(",").map { it.trim() }.filter { it.isNotEmpty() } + defaultFx.filter { false }).distinct()

    // ---- open positions, broker sync, habits, derivatives (Bybit)
    class Live(val positions: List<PositionMonitor.Pos>, val equity: Double)

    fun livePositions(c: Context): Live? {
        if (!Bybit.hasKeys(c)) return null
        val l = Bybit.positionList(c)
        val ps = (0 until l.length()).mapNotNull { BybitParse.position(l.getJSONObject(it)) }
        return Live(ps, try { Bybit.equity(c) } catch (_: Exception) { 0.0 })
    }

    private fun newsSoonFor(ev: List<MarketCalendar.Event>, now: Long): (String) -> String? = { sym ->
        MarketCalendar.upcoming(ev, now, 1, MarketCalendar.Impact.HIGH, MarketCalendar.currenciesFor(sym)).firstOrNull { it.atMillis - now <= 30 * 60_000L }?.let {
            "${it.currency} ${it.title.take(30)} in ${(it.atMillis - now) / 60_000L}m"
        }
    }

    fun positionsText(c: Context): String {
        val live = livePositions(c) ?: return "Bybit keys are not saved, so I cannot see open positions."
        val now = System.currentTimeMillis()
        val alerts = PositionMonitor.assess(live.positions, live.equity, Prefs.maxRiskPct(c).toDouble(), newsSoonFor(events(now), now))
        return PositionMonitor.summary(live.positions, live.equity) + (if (alerts.isEmpty()) "" else "\n" + alerts.joinToString("\n") { "- ${it.title}: ${it.text}" })
    }

    /** Watcher pass: alert on position risks, each at most once every 3 hours. */
    fun monitorPositions(c: Context, notify: (Int, String, String) -> Unit) {
        val live = livePositions(c) ?: return
        val now = System.currentTimeMillis()
        val alerts = PositionMonitor.assess(live.positions, live.equity, Prefs.maxRiskPct(c).toDouble(), newsSoonFor(events(now), now))
        val seen = try { JSONObject(sp(c).getString("pos_alerts", "{}")) } catch (_: Exception) { JSONObject() }
        var sent = 0
        for (a in alerts) {
            if (sent >= 3) break
            if (now - seen.optLong(a.key, 0L) < 3 * 3_600_000L) continue
            seen.put(a.key, now); notify(4500 + sent, a.title, a.text); sent++
        }
        val keep = JSONObject(); seen.keys().forEach { k -> if (now - seen.optLong(k) < 12 * 3_600_000L) keep.put(k, seen.optLong(k)) }
        sp(c).edit().putString("pos_alerts", keep.toString()).apply()
    }

    /** Pulls closed Bybit trades into the journal. Returns the trades that were new. */
    fun syncBybit(c: Context): List<ImportedTrade> {
        if (!Bybit.hasKeys(c)) return emptyList()
        val rows = Bybit.closedPnl(c, 100)
        val now = System.currentTimeMillis()
        val all = (0 until rows.length()).mapNotNull { BybitParse.imported(rows.getJSONObject(it), now) }
        val known = TradeHistoryStore.all(c).map { it.fingerprint }.toHashSet()
        val fresh = all.filter { it.fingerprint !in known }
        TradeHistoryStore.addRecords(c, fresh)
        sp(c).edit().putLong("last_sync", now).apply()
        return fresh
    }

    private fun fillsFromHistory(c: Context): List<HabitDetector.Fill> =
        TradeHistoryStore.all(c).mapNotNull { t ->
            val at = HabitDetector.parseTime(t.closedAt) ?: return@mapNotNull null
            val pnl = t.pnl ?: return@mapNotNull null
            HabitDetector.Fill(t.symbol, t.side == "long", (t.quantity ?: 0.0) * (t.entryPrice ?: 0.0), pnl, at)
        }

    fun habits(c: Context): HabitDetector.Report {
        try { syncBybit(c) } catch (_: Exception) { }
        val offset = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 3_600_000
        return HabitDetector.analyse(fillsFromHistory(c), offset)
    }

    fun derivatives(c: Context, symbol: String): DerivativesRead.Read? {
        val sym = Bybit.symbolOf(symbol) ?: return null
        return try { Bybit.derivativesInput(c, sym)?.let { DerivativesRead.read(it) } } catch (_: Exception) { null }
    }

    // ---- activity feed
    internal fun activity(c: Context): List<ActivityLog.Entry> = ActivityLog.decode(sp(c).getString("activity", null))

    private fun logActivity(c: Context, title: String, text: String) {
        try {
            synchronized(this) {
                val l = ActivityLog.add(activity(c), ActivityLog.Entry(System.currentTimeMillis(), title, text.take(300)))
                sp(c).edit().putString("activity", ActivityLog.encode(l)).apply()
            }
        } catch (_: Exception) { }
    }

    // ---- live PnL strip and companion mood
    internal class Snap(val strip: String, val state: CompanionMood.State, val realized: Double, val equity: Double, val positions: Int, val newsMinutes: Int?)

    private fun localMidnight(now: Long): Long {
        val tz = java.util.TimeZone.getDefault()
        return now - ((now + tz.getOffset(now)) % 86_400_000L + 86_400_000L) % 86_400_000L
    }

    /** Numbers behind the strip: Bybit when keys are saved, otherwise the practice account. Network work, call off the main thread. */
    internal fun pnlSnapshot(c: Context): Snap {
        val now = System.currentTimeMillis()
        val news = MarketCalendar.upcoming(events(now), now, 1, MarketCalendar.Impact.HIGH).firstOrNull()
            ?.let { ((it.atMillis - now) / 60_000L).toInt() }
        val kill = killSwitch(c)
        var open = 0.0; var realized = 0.0; var equity = 0.0; var count = 0; var noStop = 0; var streak = 0; var has = false; var practice = false
        try {
            val live = livePositions(c)
            if (live != null) {
                has = true; open = live.positions.sumOf { it.unrealisedPnl }; count = live.positions.size
                equity = live.equity; noStop = live.positions.count { it.stop == null }
                val rows = cachedClosed(c, now)
                val mid = localMidnight(now)
                val pnls = (0 until rows.length()).mapNotNull { BybitParse.fill(rows.getJSONObject(it)) }
                realized = pnls.filter { it.closedAt >= mid }.sumOf { it.pnl }
                streak = pnls.takeWhile { it.pnl < 0 }.size
            } else {
                practice = true
                val s = paper(c)
                has = s.open.isNotEmpty() || s.closed.isNotEmpty()
                count = s.open.size; equity = s.balance
                open = s.open.sumOf { p ->
                    val px = lastPrice(c, p.symbol) ?: p.entry
                    (if (p.long) 1 else -1) * (px - p.entry) / p.entry * p.usd
                }
                val mid = localMidnight(now)
                realized = s.closed.filter { it.closedAt >= mid }.sumOf { it.pnlUsd }
                streak = s.closed.asReversed().takeWhile { it.pnlUsd < 0 }.size
            }
        } catch (_: Exception) { }
        val mood = CompanionMood.from(CompanionMood.Input(open, realized, equity, streak, news, kill, practice, noStop, has))
        return Snap(if (has) PnlStrip.text(open, realized, equity, count, practice) else "No live numbers yet", mood, realized, equity, count, news)
    }

    @Volatile private var closedCache: Pair<Long, JSONArray>? = null
    private fun cachedClosed(c: Context, now: Long): JSONArray {
        closedCache?.let { if (now - it.first < 60_000L) return it.second }
        return Bybit.closedPnl(c, 100).also { closedCache = now to it }
    }

    /** One line for the top of the trading workspace: mode, kill switch, session, next high-impact news, playbook size. */
    fun statusLine(c: Context): String {
        val now = System.currentTimeMillis()
        val hour = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).hour
        val mode = when {
            Prefs.tradingReadOnly(c) -> "Read-only"
            Prefs.bybitTestnet(c) -> "Practice"
            else -> "REAL money"
        }
        val next = MarketCalendar.upcoming(events(now), now, 48, MarketCalendar.Impact.HIGH).firstOrNull()?.let {
            val m = (it.atMillis - now) / 60_000L
            "next ${it.currency} ${it.title.take(24)} in " + (if (m < 60) "${m}m" else "${m / 60}h")
        } ?: "no high-impact news in 48h"
        val n = strategies(c).size
        return "$mode · " + MarketSessions.describe(hour).substringBefore('.') + " · " + next + " · playbook $n" +
            (if (killSwitch(c)) " · KILL SWITCH ON" else "") + "  (tap for Desk)"
    }

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
                val l = a.optJSONArray("symbols")?.let { strs(it) }.orEmpty().ifEmpty { followSymbols(c) + defaultFx }
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
            "strategy_backtest" -> backtest(c, a.optString("strategy").ifBlank { null }, a.optString("symbol", followSymbols(c).firstOrNull() ?: "BTCUSDT"), a.optString("timeframe", "1h")).text
            "market_forecast" -> forecast(c, a.optString("symbol", followSymbols(c).firstOrNull() ?: "BTCUSDT"))
            "market_conditions" -> conditions(c, a.optString("symbol").ifBlank { null })
            "positions_monitor" -> positionsText(c)
            "bybit_sync" -> if (!Bybit.hasKeys(c)) "Bybit keys are not saved." else syncBybit(c).let { "Synced. ${it.size} new closed trade${if (it.size == 1) "" else "s"} added to your journal. ${TradeHistoryStore.summaryText(c)}" }
            "habit_report" -> habits(c).text()
            "crypto_derivatives" -> derivatives(c, a.optString("symbol", "BTCUSDT"))?.text ?: "No funding or open-interest data for that symbol."
            "memecoin_check" -> ChainDesk.checkMeme(a.optString("query"))
            "memecoin_watch" -> when (a.optString("action", "list")) {
                "add" -> ChainDesk.addMeme(c, a.optString("query"))
                "remove" -> ChainDesk.removeMeme(c, a.optString("query"))
                else -> ChainDesk.memes(c).let { l -> if (l.isEmpty()) "Memecoin watchlist is empty." else l.joinToString(", ") { it.symbol + " (" + it.chain + ")" } }
            }
            "wallet_check" -> ChainDesk.snapshot(a.optString("address").trim()).let { sn ->
                if (WalletAddress.looksLikeSecret(a.optString("address"))) "That looks like a private key or seed phrase. Never share those. I only need a public address."
                else if (sn == null) "Could not read that wallet right now (or it is not a valid public address)." else WalletDiff.text("Wallet", sn, null)
            }
            "wallet_watch" -> when (a.optString("action", "list")) {
                "add" -> ChainDesk.addWallet(c, a.optString("address"), a.optString("label"))
                "remove" -> ChainDesk.removeWallet(c, a.optString("address").ifBlank { a.optString("label") })
                else -> ChainDesk.wallets(c).let { l -> if (l.isEmpty()) "No wallets watched." else l.joinToString("; ") { it.label + " " + it.address.take(6) + "..." + it.address.takeLast(4) } }
            }
            "chain_overview" -> ChainDesk.overview(c)
            "trading_kill_switch" -> { setKill(c, a.optBoolean("on", true)); "Kill switch is now ${if (killSwitch(c)) "ON: no new orders will pass the risk gate" else "OFF"}." }
            else -> "Unknown trading tool $tool."
        }
    } catch (e: Exception) { "Trading tool failed: " + (e.message ?: e.javaClass.simpleName) }
}
