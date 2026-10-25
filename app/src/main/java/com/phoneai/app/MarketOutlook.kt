package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.abs

class Outlook(val symbol: String, val bias: Int, val confidence: Int, val price: Double, val text: String)

/**
 * Rule-based multi-timeframe outlook from candles. This is a structured read of trend, momentum and
 * volatility, not a forecast guarantee: confidence is capped and every call is logged and scored later.
 */
object MarketOutlook {
    private fun fmt(x: Double) = Ta.price(x)

    /** Candles oldest first; the last one may still be forming and is dropped. */
    fun compute(symbol: String, h1Raw: List<Candle>, h4Raw: List<Candle>, d1Raw: List<Candle>): Outlook? {
        val h1 = h1Raw.dropLast(1)
        val h4 = h4Raw.dropLast(1)
        val d1 = d1Raw.dropLast(1)
        if (h4.size < 60) return null
        val tH1 = Ta.trend(h1)
        val tH4 = Ta.trend(h4)
        val tD = Ta.trend(d1)
        val closes = h4.map { it.c }
        val last = closes.last()
        val rsi = Ta.rsi(closes).lastOrNull() ?: 50.0
        val back = closes[(closes.size - 7).coerceAtLeast(0)]
        val momentum = if (back == 0.0) 0.0 else (last - back) / back
        val atr = Ta.atr(h4).lastOrNull() ?: 0.0
        val atrPct = if (last == 0.0) 0.0 else atr / last * 100.0
        val recent = h4.takeLast(20)
        val support = recent.minOf { it.l }
        val resistance = recent.maxOf { it.h }

        var score = 2 * tD + 2 * tH4 + tH1
        score += if (rsi > 58) 1 else if (rsi < 42) -1 else 0
        score += if (momentum > 0.01) 1 else if (momentum < -0.01) -1 else 0
        val bias = if (score >= 3) 1 else if (score <= -3) -1 else 0
        val confidence = (30 + 8 * abs(score)).coerceAtMost(75)
        fun word(t: Int) = when (t) { 1 -> "up"; -1 -> "down"; else -> "sideways" }
        val biasWord = when (bias) { 1 -> "bullish"; -1 -> "bearish"; else -> "neutral / no clear edge" }
        val text = buildString {
            append(String.format(Locale.US, "%s outlook: %s (heuristic confidence ~%d%%, capped; not a guarantee). ", symbol, biasWord, confidence))
            append("Trend daily/4h/1h: ${word(tD)}/${word(tH4)}/${word(tH1)}. ")
            append(String.format(Locale.US, "4h RSI %.0f, 6-candle momentum %+.1f%%, 4h ATR about %.1f%% of price. ", rsi, momentum * 100, atrPct))
            append("Recent 4h range: support ${fmt(support)}, resistance ${fmt(resistance)}. ")
            when (bias) {
                1 -> append("Bullish idea is invalidated by a 4h close below ${fmt(support)}.")
                -1 -> append("Bearish idea is invalidated by a 4h close above ${fmt(resistance)}.")
                else -> append("Wait for a 4h close beyond ${fmt(support)} or ${fmt(resistance)} before taking a side.")
            }
        }
        return Outlook(symbol, bias, confidence, last, text)
    }

    /** Whether a past outlook was right: true/false, or null when price barely moved (inconclusive). */
    fun judge(bias: Int, entry: Double, now: Double, minMove: Double = 0.002): Boolean? {
        if (bias == 0 || entry <= 0.0) return null
        val move = (now - entry) / entry
        if (abs(move) < minMove) return null
        return move * bias > 0
    }
}

/** Every directional outlook the coach gives is logged and scored later, so it can report its real track record. */
object PredictionLog {
    private const val HORIZON_MS = 8L * 60 * 60 * 1000
    private val lock = Any()
    private fun file(c: Context) = File(c.filesDir, "orion_predictions.json")

    private fun load(c: Context): JSONArray = try { if (file(c).exists()) JSONArray(file(c).readText()) else JSONArray() } catch (_: Exception) { JSONArray() }
    private fun save(c: Context, a: JSONArray) { try { file(c).writeText(a.toString()) } catch (_: Exception) { } }

    fun record(c: Context, outlook: Outlook) = synchronized(lock) {
        if (outlook.bias == 0) return@synchronized
        val a = load(c)
        val now = System.currentTimeMillis()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.optString("symbol") == outlook.symbol && !o.optBoolean("done") && now - o.optLong("at") < 4L * 60 * 60 * 1000) return@synchronized
        }
        a.put(JSONObject().put("symbol", outlook.symbol).put("bias", outlook.bias).put("price", outlook.price).put("at", now).put("done", false))
        val trimmed = JSONArray()
        for (i in maxOf(0, a.length() - 200) until a.length()) trimmed.put(a.getJSONObject(i))
        save(c, trimmed)
    }

    /** Scores outlooks older than the horizon using current prices from [priceOf]. */
    fun evaluate(c: Context, priceOf: (String) -> Double?) = synchronized(lock) {
        val a = load(c)
        val now = System.currentTimeMillis()
        var changed = false
        val cache = HashMap<String, Double?>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.optBoolean("done") || now - o.optLong("at") < HORIZON_MS) continue
            val symbol = o.optString("symbol")
            val price = cache.getOrPut(symbol) { try { priceOf(symbol) } catch (_: Exception) { null } } ?: continue
            val verdict = MarketOutlook.judge(o.optInt("bias"), o.optDouble("price"), price)
            o.put("done", true).put("result", when (verdict) { true -> "hit"; false -> "miss"; null -> "flat" })
            changed = true
        }
        if (changed) save(c, a)
    }

    fun statsText(c: Context): String = synchronized(lock) {
        val a = load(c)
        var hit = 0; var miss = 0; var pending = 0
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (!o.optBoolean("done")) pending++ else when (o.optString("result")) { "hit" -> hit++; "miss" -> miss++ }
        }
        val scored = hit + miss
        if (scored == 0) "No scored outlooks yet ($pending waiting for the 8 hour check)."
        else String.format(Locale.US, "Past outlooks scored after 8h: %d right, %d wrong (%.0f%% of %d); %d still waiting. Small sample; do not over-trust.", hit, miss, hit * 100.0 / scored, scored, pending)
    }
}

/** Lines the coach appends to its reply so it can remember charts, mistakes and strategy rules. */
object CoachMemory {
    class Parsed(val visible: String, val notes: List<Pair<String, String>>)

    private val marker = Regex("(?im)^\\s*(MEMORY|MISTAKE|RULES)\\s*:\\s*(.+)$")

    fun parse(reply: String): Parsed {
        val notes = ArrayList<Pair<String, String>>()
        for (m in marker.findAll(reply)) {
            val tag = when (m.groupValues[1].uppercase()) { "MEMORY" -> "chart"; "MISTAKE" -> "mistake"; else -> "rule" }
            val body = m.groupValues[2].trim()
            if (tag == "rule") body.split("|").map { it.trim() }.filter { it.length in 6..200 }.take(6).forEach { notes.add(tag to it) }
            else if (body.length in 6..240) notes.add(tag to body)
        }
        val clean = reply.replace(marker, "").replace(Regex("\\n{3,}"), "\n\n").trim()
        return Parsed(clean, notes.filter { !SafetyGate.containsSensitiveCredentialText(it.second) }.take(8))
    }
}
