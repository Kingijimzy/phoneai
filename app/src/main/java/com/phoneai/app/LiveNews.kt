package com.phoneai.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Recent public-news lookup via GDELT's open DOC API; no model call or API key required. */
object LiveNews {
    private const val TIMEOUT_MS = 15_000

    fun isNewsRequest(text: String): Boolean {
        val s = text.lowercase()
        val asksNews = Regex("\\b(news|headlines|latest updates|breaking news|current events)\\b").containsMatchIn(s)
        val asksNow = Regex("\\b(latest|today|now|current|recent|breaking|what'?s happening|what is happening|updates|headlines)\\b").containsMatchIn(s)
        return asksNews && asksNow
    }

    fun queryFrom(text: String): String {
        val cleaned = text.trim()
            .replace(Regex("(?i)\\b(what'?s|what is|give me|show me|find|search for|tell me|latest|recent|breaking|news|headlines|updates|today|now|about|on|please|current)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '.', '?', '!', ',')
        return cleaned.replace(Regex("(?i)^(in|for|from)\\s+"), "").take(120).ifBlank { "world news" }
    }

    /** Google News RSS: real headlines with source and publish time; no key. Returns null on any failure. */
    fun rss(q: String): String? = try {
        val url = "https://news.google.com/rss/search?q=" + URLEncoder.encode("$q when:2d", "UTF-8") + "&hl=en-US&gl=US&ceid=US:en"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 10_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Orion")
        }
        val xml = try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().take(500_000) }
        } finally { conn.disconnect() }
        val items = parseRss(xml, 8)
        if (items.isEmpty()) null
        else "Latest headlines for \"$q\" (Google News RSS):\n" + items.mapIndexed { i, it -> "${i + 1}. $it" }.joinToString("\n")
    } catch (_: Exception) { null }

    internal fun parseRss(xml: String, max: Int): List<String> {
        fun unesc(t: String) = t.replace("<![CDATA[", "").replace("]]>", "").replace("&amp;", "&").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").trim()
        val out = ArrayList<String>()
        for (m in Regex("(?s)<item>(.*?)</item>").findAll(xml)) {
            val block = m.groupValues[1]
            val title = Regex("(?s)<title>(.*?)</title>").find(block)?.groupValues?.get(1)?.let(::unesc).orEmpty()
            val source = Regex("(?s)<source[^>]*>(.*?)</source>").find(block)?.groupValues?.get(1)?.let(::unesc).orEmpty()
            val date = Regex("(?s)<pubDate>(.*?)</pubDate>").find(block)?.groupValues?.get(1)?.trim().orEmpty()
            if (title.isBlank()) continue
            out.add(title.take(220) + (if (source.isNotBlank()) " — $source" else "") + (if (date.isNotBlank()) " ($date)" else ""))
            if (out.size >= max) break
        }
        return out
    }

    fun search(query: String): String {
        val q = query.trim().take(120)
        if (q.isBlank()) return "What topic should I search for in recent news?"
        rss(q)?.let { return it }
        return try {
            val encoded = URLEncoder.encode(q, "UTF-8")
            val url = "https://api.gdeltproject.org/api/v2/doc/doc?query=$encoded&mode=ArtList&format=json&sort=DateDesc&timespan=1week&maxrecords=5"
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "PhoneAI/1.0 (recent news lookup)")
                instanceFollowRedirects = true
            }
            val body: String
            try {
                val code = connection.responseCode
                if (code !in 200..299) throw IllegalStateException("news service returned HTTP $code")
                body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().take(700_000) }
            } finally {
                connection.disconnect()
            }
            val articles = JSONObject(body).optJSONArray("articles")
            if (articles == null || articles.length() == 0) return "I couldn't find recent public news matching \"$q\". Try a more specific topic."
            val out = StringBuilder("Recent public news about \"$q\" (GDELT; results can vary by region):")
            for (i in 0 until minOf(articles.length(), 5)) {
                val a = articles.optJSONObject(i) ?: continue
                val title = a.optString("title", "").trim()
                val link = a.optString("url", "").trim()
                val domain = a.optString("domain", "").trim()
                val date = a.optString("seendate", "").trim()
                if (title.isBlank() || !link.startsWith("https://")) continue
                out.append("\n\n").append(i + 1).append(". ").append(title.take(240))
                if (domain.isNotBlank()) out.append(" — ").append(domain.take(80))
                if (date.isNotBlank()) out.append(" (seen ").append(date).append(")")
                out.append("\n").append(link.take(500))
            }
            out.append("\n\nSource: GDELT public news index. Open the links to verify details.")
            out.toString()
        } catch (e: Exception) {
            // GDELT occasionally stalls. Fall back to the app's separate public web-search
            // path instead of treating one news-index timeout as a total failure.
            val fallbackQuery = if (q.contains("crypto", true) || q.contains("bitcoin", true)) {
                "crypto news today latest"
            } else {
                "$q latest news today"
            }
            val fallback = try { LiveWebSearch.search(fallbackQuery) } catch (_: Exception) { "" }
            if (fallback.startsWith("Web search results for")) {
                "The dedicated news index timed out, so these are fallback web-search results instead. Check article dates and sources.\n\n" + fallback
            } else {
                "I couldn't retrieve recent news from either source right now (news index: " +
                    (e.message?.take(70) ?: "network error") + "; web-search fallback unavailable). This lookup does not use AI quota. Please retry or open browser search."
            }
        }
    }
}
