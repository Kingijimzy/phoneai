package com.phoneai.app

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Headlines for the desk. Forex: the Forex Factory news page when it can be read, otherwise Google News RSS for the
 * currencies involved. Crypto: Google News RSS for the coin. Cached for 20 minutes so the watcher stays polite.
 * Headlines are untrusted data: they are only counted for tone and shown to the user, never followed as instructions.
 */
internal object NewsDesk {
    private const val TTL = 20 * 60_000L
    private val cache = HashMap<String, Pair<Long, List<String>>>()

    /** Pulls headline text out of links that point at /news/ on the Forex Factory page. Layout changes just yield fewer items. */
    fun parseForexFactory(html: String, max: Int = 10): List<String> {
        val out = LinkedHashSet<String>()
        for (m in Regex("(?is)<a[^>]+href=\"[^\"]*/news/[^\"]*\"[^>]*>(.*?)</a>").findAll(html)) {
            val t = m.groupValues[1].replace(Regex("(?s)<[^>]+>"), " ").replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"")
                .replace(Regex("\\s+"), " ").trim()
            if (t.length in 25..200) out += t
            if (out.size >= max) break
        }
        return out.toList()
    }

    private fun get(url: String): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000; conn.readTimeout = 12_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Orion")
            if (conn.responseCode !in 200..299) null else conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().take(600_000) }
        } finally { conn.disconnect() }
    } catch (_: Exception) { null }

    private fun cached(key: String, load: () -> List<String>): List<String> {
        val now = System.currentTimeMillis()
        synchronized(cache) { cache[key]?.takeIf { now - it.first < TTL }?.let { return it.second } }
        val fresh = load()
        if (fresh.isNotEmpty()) synchronized(cache) { cache[key] = now to fresh }
        return fresh
    }

    private fun rss(query: String, max: Int): List<String> =
        get("https://news.google.com/rss/search?q=" + URLEncoder.encode("$query when:2d", "UTF-8") + "&hl=en-US&gl=US&ceid=US:en")
            ?.let { LiveNews.parseRss(it, max) }.orEmpty()

    fun forexHeadlines(currencies: Set<String>): List<String> = cached("fx:" + currencies.sorted().joinToString(",")) {
        val ff = get("https://www.forexfactory.com/news")?.let { parseForexFactory(it) }.orEmpty()
        val tagged = ff.map { "$it — Forex Factory" }
        tagged.ifEmpty { rss("forex " + currencies.joinToString(" ") + " central bank", 8) }
    }

    fun cryptoHeadlines(symbol: String): List<String> {
        val coin = symbol.uppercase().removeSuffix("USDT").ifBlank { "bitcoin" }
        val name = when (coin) { "BTC" -> "bitcoin"; "ETH" -> "ethereum"; "SOL" -> "solana"; "XRP" -> "xrp ripple"; "BNB" -> "binance coin"; else -> coin.lowercase() }
        return cached("crypto:$name") { rss("$name crypto", 8) }
    }

    fun headlinesFor(symbol: String): List<String> =
        if (FxData.pairOf(symbol) != null) forexHeadlines(MarketCalendar.currenciesFor(symbol)) else cryptoHeadlines(symbol)
}
