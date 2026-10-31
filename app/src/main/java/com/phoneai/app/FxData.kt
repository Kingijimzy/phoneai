package com.phoneai.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Forex, gold and index candles from the public Yahoo chart feed, in the same Candle type the crypto scanner uses. */
internal object FxData {
    private val majors = setOf(
        "EURUSD", "GBPUSD", "USDJPY", "USDCHF", "AUDUSD", "NZDUSD", "USDCAD", "EURGBP", "EURJPY", "GBPJPY",
        "AUDJPY", "EURAUD", "EURCHF", "GBPCHF", "CADJPY", "XAUUSD", "XAGUSD"
    )

    /** "eur/usd", "EUR_USD", "EURUSD=X" -> EURUSD, or null when it is not a known forex or metal pair. */
    fun pairOf(word: String): String? {
        val s = word.uppercase(Locale.US).replace(Regex("[^A-Z]"), "").removeSuffix("X")
        return s.takeIf { it in majors }
    }

    fun yahooSymbol(pair: String): String = pair.uppercase(Locale.US) + "=X"

    /** interval: "15m", "60m", "1d"; Yahoo has no 4h, so 4h is built from 60m. */
    fun parseChart(json: String): List<Candle> {
        val root = try { JSONObject(json) } catch (_: Exception) { return emptyList() }
        val res = root.optJSONObject("chart")?.optJSONArray("result")?.optJSONObject(0) ?: return emptyList()
        val ts = res.optJSONArray("timestamp") ?: return emptyList()
        val q = res.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0) ?: return emptyList()
        val o = q.optJSONArray("open"); val h = q.optJSONArray("high"); val l = q.optJSONArray("low"); val c = q.optJSONArray("close"); val v = q.optJSONArray("volume")
        if (o == null || h == null || l == null || c == null) return emptyList()
        val out = ArrayList<Candle>(ts.length())
        for (i in 0 until ts.length()) {
            if (o.isNull(i) || h.isNull(i) || l.isNull(i) || c.isNull(i)) continue // Yahoo leaves gaps as null
            out += Candle(ts.getLong(i) * 1000L, o.getDouble(i), h.getDouble(i), l.getDouble(i), c.getDouble(i), if (v != null && !v.isNull(i)) v.getDouble(i) else 0.0)
        }
        return out
    }

    /** Merge consecutive hourly candles into [n]-hour candles aligned to UTC hours. */
    fun aggregate(candles: List<Candle>, hours: Int): List<Candle> {
        if (hours <= 1) return candles
        val bucket = hours * 3_600_000L
        return candles.groupBy { it.t / bucket }.toSortedMap().values.map { g ->
            Candle(g.first().t, g.first().o, g.maxOf { it.h }, g.minOf { it.l }, g.last().c, g.sumOf { it.v })
        }
    }

    fun candles(pair: String, tf: String): List<Candle> {
        val (interval, range) = when (tf) { "15m" -> "15m" to "5d"; "1h" -> "60m" to "1mo"; "4h" -> "60m" to "3mo"; else -> "1d" to "1y" }
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/${yahooSymbol(pair)}?interval=$interval&range=$range"
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 12_000; conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (conn.responseCode !in 200..299) emptyList()
            else parseChart(conn.inputStream.bufferedReader().use { it.readText() }).let { if (tf == "4h") aggregate(it, 4) else it }
        } catch (_: Exception) { emptyList() } finally { conn.disconnect() }
    }
}
