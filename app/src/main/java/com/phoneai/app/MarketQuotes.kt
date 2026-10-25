package com.phoneai.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Keyless public quotes (Yahoo Finance chart endpoint) for forex, metals, indices and stocks that Bybit does not list. */
object MarketQuotes {
    class Quote(val label: String, val price: Double, val prevClose: Double, val currency: String, val high: Double, val low: Double) {
        val changePct: Double get() = if (prevClose > 0) (price - prevClose) / prevClose * 100.0 else 0.0
        fun text(): String = String.format(Locale.US, "%s: %s %s (%+.2f%% vs previous close; day range %s - %s)",
            label, fmt(price), currency, changePct, fmt(low), fmt(high))
    }

    private val forexCodes = setOf("USD", "EUR", "GBP", "JPY", "CHF", "AUD", "NZD", "CAD", "NGN", "ZAR", "CNY", "MXN", "TRY", "SGD", "HKD", "INR")
    private val named = mapOf(
        "gold" to ("GC=F" to "Gold futures"), "xauusd" to ("XAUUSD=X" to "XAU/USD (gold)"),
        "silver" to ("SI=F" to "Silver futures"), "xagusd" to ("XAGUSD=X" to "XAG/USD (silver)"),
        "oil" to ("CL=F" to "WTI crude oil"), "wti" to ("CL=F" to "WTI crude oil"), "brent" to ("BZ=F" to "Brent crude"),
        "nasdaq" to ("^IXIC" to "Nasdaq Composite"), "nas100" to ("^NDX" to "Nasdaq 100"), "us100" to ("^NDX" to "Nasdaq 100"),
        "sp500" to ("^GSPC" to "S&P 500"), "spx" to ("^GSPC" to "S&P 500"), "us500" to ("^GSPC" to "S&P 500"),
        "dow" to ("^DJI" to "Dow Jones"), "us30" to ("^DJI" to "Dow Jones"), "dxy" to ("DX-Y.NYB" to "US Dollar Index"),
        "vix" to ("^VIX" to "VIX"), "dax" to ("^GDAXI" to "DAX"), "ftse" to ("^FTSE" to "FTSE 100"), "nikkei" to ("^N225" to "Nikkei 225"),
        "tesla" to ("TSLA" to "Tesla"), "apple" to ("AAPL" to "Apple"), "nvidia" to ("NVDA" to "Nvidia"),
        "tsla" to ("TSLA" to "Tesla"), "aapl" to ("AAPL" to "Apple"), "nvda" to ("NVDA" to "Nvidia"), "msft" to ("MSFT" to "Microsoft")
    )

    /** Returns (yahooSymbol, label) when [token] is a forex pair or a known non-crypto instrument. */
    fun resolve(token: String): Pair<String, String>? {
        val t = token.trim().lowercase(Locale.ROOT).replace("/", "")
        named[t]?.let { return it }
        val u = t.uppercase(Locale.ROOT)
        if (u.length == 6 && u.substring(0, 3) in forexCodes && u.substring(3) in forexCodes && u.substring(0, 3) != u.substring(3)) {
            return (u + "=X") to (u.substring(0, 3) + "/" + u.substring(3))
        }
        return null
    }

    fun symbolsIn(text: String): List<Pair<String, String>> =
        text.split(Regex("[^A-Za-z0-9/]+")).mapNotNull { resolve(it) }.distinctBy { it.first }.take(3)

    fun quote(yahooSymbol: String, label: String): Quote {
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/" + java.net.URLEncoder.encode(yahooSymbol, "UTF-8") + "?interval=1d&range=5d"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 10_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Orion")
            setRequestProperty("Accept", "application/json")
        }
        val body = try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("quote service HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText().take(400_000) }
        } finally { conn.disconnect() }
        val meta = JSONObject(body).getJSONObject("chart").optJSONArray("result")?.optJSONObject(0)?.optJSONObject("meta")
            ?: throw IllegalStateException("no quote data for $label")
        val price = meta.optDouble("regularMarketPrice", Double.NaN)
        if (price.isNaN()) throw IllegalStateException("no price for $label")
        val prev = meta.optDouble("chartPreviousClose", meta.optDouble("previousClose", price))
        return Quote(label, price, prev, meta.optString("currency", ""), meta.optDouble("regularMarketDayHigh", price), meta.optDouble("regularMarketDayLow", price))
    }

    /** Plain-text live quotes for every non-crypto instrument named in [text]; empty when none. */
    fun contextFor(text: String): String {
        val sb = StringBuilder()
        for ((sym, label) in symbolsIn(text)) {
            sb.append("\nLive ").append(try { quote(sym, label).text() } catch (e: Exception) { "$label: quote unavailable (${e.message?.take(60)})" })
        }
        return sb.toString()
    }

    private fun fmt(v: Double): String = if (v >= 100) String.format(Locale.US, "%.2f", v) else String.format(Locale.US, "%.5f", v)
}
