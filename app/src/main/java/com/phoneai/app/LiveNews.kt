package com.phoneai.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Recent public-news lookup via GDELT's open DOC API; no model call or API key required. */
object LiveNews {
    private const val TIMEOUT_MS = 8_000

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
        return cleaned.take(120).ifBlank { "world news" }
    }

    fun search(query: String): String {
        val q = query.trim().take(120)
        if (q.isBlank()) return "What topic should I search for in recent news?"
        return try {
            val encoded = URLEncoder.encode(q, "UTF-8")
            val url = "https://api.gdeltproject.org/api/v2/doc/doc?query=$encoded&mode=ArtList&format=json&sort=DateDesc&maxrecords=5"
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "PhoneAI/1.0 (recent news lookup)")
                instanceFollowRedirects = false
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
            "I couldn't retrieve recent news right now (" + (e.message?.take(100) ?: "network error") + "). This lookup does not use AI quota; try again when the service is reachable."
        }
    }
}
