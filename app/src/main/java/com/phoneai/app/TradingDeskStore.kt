package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Local trading desk state: playbook candidates/rules + risk envelope bridged to Prefs.
 * Does not place orders.
 */
object TradingDeskStore {
    private fun p(c: Context) = c.getSharedPreferences("phoneai_trading_desk", Context.MODE_PRIVATE)

    fun riskLimits(c: Context): TradingRiskPolicy.Limits {
        val allow = Prefs.watchlist(c).split(",", " ", "\n")
            .map { TradingPlaybook.normalizeInstrument(it) }
            .filter { it.isNotBlank() }
            .toSet()
        return TradingRiskPolicy.Limits(
            killSwitch = Prefs.tradingReadOnly(c),
            paperMode = Prefs.bybitTestnet(c),
            maxNotionalUsd = Prefs.maxOrderUsd(c).toDouble().coerceAtLeast(1.0),
            maxDailyLossUsd = Prefs.maxOrderUsd(c).toDouble().coerceAtLeast(1.0),
            maxOpenTrades = 5,
            symbolAllowlist = allow
        )
    }

    fun watchSymbols(c: Context): List<String> =
        Prefs.watchlist(c).split(",", " ", "\n").map { TradingPlaybook.normalizeInstrument(it) }.filter { it.isNotBlank() }.distinct()

    fun rules(c: Context): List<TradingPlaybook.Rule> = runCatching {
        val a = JSONArray(p(c).getString("playbook_rules", "[]") ?: "[]")
        (0 until a.length()).mapNotNull { i -> ruleFromJson(a.getJSONObject(i)) }
    }.getOrDefault(emptyList())

    fun candidates(c: Context): List<Pair<String, TradingPlaybook.Candidate>> = runCatching {
        val a = JSONArray(p(c).getString("playbook_candidates", "[]") ?: "[]")
        (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            val id = o.optString("id")
            val cand = candidateFromJson(o) ?: return@mapNotNull null
            id to cand
        }
    }.getOrDefault(emptyList())

    fun proposeCandidate(c: Context, candidate: TradingPlaybook.Candidate): String {
        val id = UUID.randomUUID().toString().take(12)
        val a = JSONArray(p(c).getString("playbook_candidates", "[]") ?: "[]")
        a.put(candidateToJson(id, candidate))
        p(c).edit().putString("playbook_candidates", a.toString()).apply()
        return id
    }

    fun acceptCandidate(c: Context, candidateId: String, enable: Boolean = true): Boolean {
        val list = candidates(c)
        val hit = list.firstOrNull { it.first == candidateId } ?: return false
        val rule = TradingPlaybook.activate(hit.second, candidateId, enable)
        saveRules(c, rules(c) + rule)
        val remaining = JSONArray()
        list.filter { it.first != candidateId }.forEach { remaining.put(candidateToJson(it.first, it.second)) }
        p(c).edit().putString("playbook_candidates", remaining.toString()).apply()
        return true
    }

    fun rejectCandidate(c: Context, candidateId: String) {
        val remaining = JSONArray()
        candidates(c).filter { it.first != candidateId }.forEach { remaining.put(candidateToJson(it.first, it.second)) }
        p(c).edit().putString("playbook_candidates", remaining.toString()).apply()
    }

    fun setRuleEnabled(c: Context, ruleId: String, enabled: Boolean) {
        saveRules(c, rules(c).map { if (it.id == ruleId) it.copy(enabled = enabled) else it })
    }

    fun matchWatchlist(c: Context): List<TradingMatcher.SetupCard> {
        val enabledRules = rules(c).filter { it.enabled }
        if (enabledRules.isEmpty()) return emptyList()
        val out = ArrayList<TradingMatcher.SetupCard>()
        for (sym in watchSymbols(c)) {
            val asset = TradingPlaybook.parseAssetClass(sym)
            val px: Double?
            val ch: Double?
            if (asset == TradingPlaybook.AssetClass.FOREX || MarketQuotes.resolve(sym.replace("/", "")) != null) {
                val resolved = MarketQuotes.resolve(sym.replace("/", ""))
                if (resolved != null) {
                    val q = runCatching { MarketQuotes.quote(resolved.first, resolved.second) }.getOrNull()
                    px = q?.price
                    ch = q?.let {
                        if (it.prevClose != 0.0) ((it.price - it.prevClose) / it.prevClose) * 100.0 else null
                    }
                } else {
                    px = null; ch = null
                }
            } else {
                px = runCatching { Bybit.price(c, sym) }.getOrNull()
                ch = runCatching { Bybit.change24h(c, sym) }.getOrNull()
            }
            val snap = TradingMatcher.MarketSnapshot(
                instrument = sym,
                assetClass = asset,
                lastPrice = px,
                changePct = ch,
                session = TradingCalendar.sessionNameUtc()
            )
            out += TradingMatcher.match(enabledRules, snap)
        }
        return out.sortedByDescending { it.score }
    }

    private fun saveRules(c: Context, list: List<TradingPlaybook.Rule>) {
        val a = JSONArray()
        list.take(100).forEach { a.put(ruleToJson(it)) }
        p(c).edit().putString("playbook_rules", a.toString()).apply()
    }

    private fun ruleToJson(r: TradingPlaybook.Rule) = JSONObject()
        .put("id", r.id)
        .put("name", r.name)
        .put("assetClass", r.assetClass.name)
        .put("instrument", r.instrument)
        .put("direction", r.direction.name)
        .put("enabled", r.enabled)
        .put("version", r.version)
        .put("sourceNote", r.sourceNote)
        .put("conditions", JSONArray(r.conditions))

    private fun ruleFromJson(o: JSONObject): TradingPlaybook.Rule? = runCatching {
        val cond = o.optJSONArray("conditions") ?: JSONArray()
        TradingPlaybook.Rule(
            id = o.getString("id"),
            name = o.optString("name"),
            assetClass = TradingPlaybook.AssetClass.valueOf(o.optString("assetClass", "CRYPTO")),
            instrument = TradingPlaybook.normalizeInstrument(o.optString("instrument")),
            direction = TradingPlaybook.Direction.valueOf(o.optString("direction", "EITHER")),
            conditions = (0 until cond.length()).map { cond.getString(it) },
            enabled = o.optBoolean("enabled", false),
            version = o.optInt("version", 1),
            sourceNote = o.optString("sourceNote")
        )
    }.getOrNull()

    private fun candidateToJson(id: String, c: TradingPlaybook.Candidate) = JSONObject()
        .put("id", id)
        .put("name", c.name)
        .put("assetClass", c.assetClass.name)
        .put("instrument", c.instrument)
        .put("direction", c.direction.name)
        .put("sourceNote", c.sourceNote)
        .put("conditions", JSONArray(c.conditions))

    private fun candidateFromJson(o: JSONObject): TradingPlaybook.Candidate? = runCatching {
        val cond = o.optJSONArray("conditions") ?: JSONArray()
        TradingPlaybook.Candidate(
            name = o.optString("name"),
            assetClass = TradingPlaybook.AssetClass.valueOf(o.optString("assetClass", "CRYPTO")),
            instrument = TradingPlaybook.normalizeInstrument(o.optString("instrument")),
            direction = TradingPlaybook.Direction.valueOf(o.optString("direction", "EITHER")),
            conditions = (0 until cond.length()).map { cond.getString(it) },
            sourceNote = o.optString("sourceNote"),
            needsUserAccept = true
        )
    }.getOrNull()
}
