package com.phoneai.app

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.net.URLDecoder
import java.util.regex.Pattern

/** Lightweight public web search via DuckDuckGo HTML results; no AI API key or model call. */
object LiveWebSearch {
    private const val TIMEOUT_MS = 8_000
    private val anchorPattern = Pattern.compile("(?is)<a\\b[^>]*>.*?</a>")
    private val hrefPattern = Pattern.compile("(?i)href\\s*=\\s*[\"']([^\"']+)[\"']")
    private val titlePattern = Pattern.compile("(?is)<a\\b[^>]*>(.*?)</a>")
    private val snippetPattern = Pattern.compile("(?is)<(?:a|div|span)[^>]*class=[\"'][^\"']*result__snippet[^\"']*[\"'][^>]*>(.*?)</(?:a|div|span)>")

    fun isSearchRequest(text: String): Boolean {
        val s = text.lowercase()
        return Regex("\\b(search the web|search online|web search|look up online|look this up|find online|search for|research online)\\b").containsMatchIn(s)
    }

    /** Detects freshness-dependent questions even when the user does not say "search the web". */
    fun isLiveInfoRequest(text: String): Boolean {
        val s = text.lowercase()
        if (Regex("\\b(weather|forecast|temperature|bitcoin|btc|ethereum|eth|solana|crypto price|stock price|forex price|exchange rate|time is it|what time|today's date|current time)\\b").containsMatchIn(s)) return false
        return Regex("\\b(latest|currently|right now|as of today|today's|today|breaking|recently|this week|this month|current events|who is the current|what is happening|what happened today|latest update|new update|current status|is .+ still)\\b").containsMatchIn(s)
    }

    fun liveQueryFrom(text: String): String = text.trim()
        .replace(Regex("(?i)\\b(please|tell me|can you|could you|what is|what's|what are|who is|who's|give me|find out|check|right now|currently|as of today|latest|current|today|recently|breaking)\\b"), " ")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '.', '?', '!', ',')
        .ifBlank { text.trim() }
        .take(160)

    fun queryFrom(text: String): String = text.trim()
        .replace(Regex("(?i)\\b(search the web for|search online for|web search for|search for|look up online|look this up|find online|research online|please|on the internet|online)\\b"), " ")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '.', '?', '!', ',')
        .take(160)

    fun search(query: String): String {
        val q = query.trim().take(160)
        if (q.isBlank()) return "What should I search for?"
        return try {
            val url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(q, "UTF-8")
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "text/html")
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/130 Mobile Safari/537.36")
                instanceFollowRedirects = true
            }
            val html: String
            try {
                val code = connection.responseCode
                if (code !in 200..299) throw IllegalStateException("search service returned HTTP $code")
                html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().take(700_000) }
            } finally {
                connection.disconnect()
            }
            val anchors = anchorPattern.matcher(html)
            val out = StringBuilder("Web search results for \"$q\" (verify sources before relying on them):")
            var count = 0
            while (anchors.find() && count < 5) {
                val tag = anchors.group()
                if (!tag.contains("result__a", ignoreCase = true)) continue
                val href = hrefPattern.matcher(tag)
                if (!href.find()) continue
                var link = decodeHtml(href.group(1))
                if (link.startsWith("//")) link = "https:$link"
                if (link.contains("duckduckgo.com/l/?")) {
                    val target = Regex("[?&]uddg=([^&]+)").find(link)?.groupValues?.get(1)
                    if (target != null) link = try { URLDecoder.decode(target, "UTF-8") } catch (_: Exception) { link }
                }
                if (!link.startsWith("https://") || link.contains("duckduckgo.com/l/?")) continue
                val titleMatch = titlePattern.matcher(tag)
                if (!titleMatch.find()) continue
                val title = decodeHtml(titleMatch.group(1).replace(Regex("(?is)<[^>]+>"), " ")).trim()
                if (title.isBlank()) continue
                count++
                out.append("\n\n").append(count).append(". ").append(title.take(220)).append("\n").append(link.take(500))
            }
            if (count == 0) {
                "The search provider did not return parseable results just now. Try again or open a browser search. No AI quota was used."
            } else {
                val snippets = snippetPattern.matcher(html)
                var idx = 0
                while (snippets.find() && idx < count) {
                    val text = decodeHtml(snippets.group(1).replace(Regex("(?is)<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim()
                    if (text.isNotBlank()) out.append("\n\nSnippet: ").append(text.take(300))
                    idx++
                }
                out.append("\n\nSource: DuckDuckGo public search results. Search availability and layout can change.")
                out.toString()
            }
        } catch (e: Exception) {
            "I couldn't retrieve web search results right now (" + (e.message?.take(100) ?: "network error") + "). No AI quota was used; try again or open a browser search."
        }
    }

    private fun decodeHtml(value: String): String = value
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'")
        .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&nbsp;", " ").replace("&#x2F;", "/")
}
