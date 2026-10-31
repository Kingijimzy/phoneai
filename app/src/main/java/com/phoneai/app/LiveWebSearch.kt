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
        return Regex("\\b(search the web|search online|web search|look up online|look this up|find online|search for|research online|do your research|research this|look it up|google it)\\b").containsMatchIn(s)
    }

    /** Detects freshness-dependent questions even when the user does not say "search the web". */
    fun isLiveInfoRequest(text: String): Boolean {
        val s = text.lowercase()
        // Route price-only questions to quote providers, but never suppress news/research merely
        // because the asset name appears. "Bitcoin news today" must trigger web research.
        if (Regex("\\b(weather|forecast|temperature|time is it|what time|today's date|current time)\\b").containsMatchIn(s)) return false
        val priceOnly = Regex("\\b(price|quote|trading at|how much is|value of|market price)\\b").containsMatchIn(s)
        val asset = Regex("\\b(bitcoin|btc|ethereum|eth|solana|crypto|stock|forex|exchange rate)\\b").containsMatchIn(s)
        if (priceOnly && asset && !Regex("\\b(news|headline|headlines|why|explain|analysis|research|update|happening)\\b").containsMatchIn(s)) return false
        return Regex("\\b(latest|currently|right now|as of today|today's|today|breaking|recently|this week|this month|current events|who is the current|who won|winner|winners|ranking|rankings|results|ballon d'or|award winners|top 10|what is happening|what happened today|latest update|new update|current status|is .+ still)\\b").containsMatchIn(s)
    }

    fun liveQueryFrom(text: String): String = text.trim()
        .replace(Regex("(?i)\\b(please|tell me|can you|could you|what is|what's|what are|who is|who's|give me|find out|check|right now|currently|as of today|latest|current|today|recently|breaking)\\b"), " ")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '.', '?', '!', ',')
        .ifBlank { text.trim() }
        .take(160)

    fun queryFrom(text: String): String = text.trim()
        .replace(Regex("(?i)\\b(search the web for|search online for|web search for|search for|look up online|look this up|look it up|find online|research online|do your research|research this|google it|please|on the internet|online)\\b"), " ")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '.', '?', '!', ',')
        .take(160)

    fun search(query: String): String {
        val q = query.trim().take(160)
        if (q.isBlank()) return "What should I search for?"
        val retrievedAt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", java.util.Locale.US).format(java.util.Date())
        // Independent provider fallback matters on mobile networks where one search host
        // may be blocked, rate-limited, or return a changed HTML layout.
        val endpoints = listOf(
            "https://html.duckduckgo.com/html/?q=",
            "https://lite.duckduckgo.com/lite/?q=",
            "https://www.google.com/search?q="
        )
        val failures = mutableListOf<String>()
        for ((endpointIndex, endpoint) in endpoints.withIndex()) {
            try {
                val url = endpoint + URLEncoder.encode(q, "UTF-8")
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 4500
                    readTimeout = 5500
                    setRequestProperty("Accept", "text/html")
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/130 Mobile Safari/537.36")
                    setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                    instanceFollowRedirects = true
                }
                val html: String
                try {
                    val code = connection.responseCode
                    if (code !in 200..299) throw IllegalStateException("search service returned HTTP " + code)
                    html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().take(700_000) }
                } finally {
                    connection.disconnect()
                }
                val anchors = anchorPattern.matcher(html)
                val out = StringBuilder("Web search results for \"").append(q)
                    .append("\" (retrieved ").append(retrievedAt)
                    .append("; verify sources before relying on them):")
                val seen = HashSet<String>()
                var count = 0
                while (anchors.find() && count < 5) {
                    val tag = anchors.group()
                    val href = hrefPattern.matcher(tag)
                    if (!href.find()) continue
                    var link = decodeHtml(href.group(1))
                    if (link.startsWith("//")) link = "https:" + link
                    if (link.contains("duckduckgo.com/l/?")) {
                        val target = Regex("[?&]uddg=([^&]+)").find(link)?.groupValues?.get(1)
                        if (target != null) link = try { URLDecoder.decode(target, "UTF-8") } catch (_: Exception) { link }
                    } else if (link.startsWith("/url?")) {
                        val target = Regex("[?&](?:q|url)=([^&]+)").find(link)?.groupValues?.get(1)
                        if (target != null) link = try { URLDecoder.decode(target, "UTF-8") } catch (_: Exception) { link }
                    }
                    if (!link.startsWith("https://") || link.contains("duckduckgo.com") ||
                        link.contains("google.com/url") || link.contains("javascript:") || link in seen) continue
                    val titleMatch = titlePattern.matcher(tag)
                    if (!titleMatch.find()) continue
                    val title = decodeHtml(titleMatch.group(1).replace(Regex("(?is)<[^>]+>"), " "))
                        .replace(Regex("\\s+"), " ").trim()
                    if (title.isBlank() || title.equals("more", true) || title.equals("next", true)) continue
                    // Prefer marked result links, but accept valid external anchors on the lite layout.
                    val marked = tag.contains("result__a", ignoreCase = true) ||
                        tag.contains("result-link", ignoreCase = true) ||
                        tag.contains("result__url", ignoreCase = true) ||
                        (endpointIndex == 2 && tag.contains("LC20lb", ignoreCase = true))
                    if (!marked) continue
                    seen.add(link)
                    count++
                    out.append("\n\n").append(count).append(". ").append(title.take(220))
                        .append("\n").append(link.take(500))
                }
                if (count > 0) {
                    val snippets = snippetPattern.matcher(html)
                    var idx = 0
                    while (snippets.find() && idx < count) {
                        val snippet = decodeHtml(snippets.group(1).replace(Regex("(?is)<[^>]+>"), " "))
                            .replace(Regex("\\s+"), " ").trim()
                        if (snippet.isNotBlank()) out.append("\n\nSnippet: ").append(snippet.take(300))
                        idx++
                    }
                    out.append("\n\nSource: DuckDuckGo public search results (")
                        .append(when (endpointIndex) { 0 -> "HTML"; 1 -> "lite fallback"; else -> "Google fallback" })
                        .append("). Search availability and layout can change.")
                    return out.toString()
                }
                failures.add(if (endpointIndex == 0) "HTML results were not parseable" else "lite results were not parseable")
            } catch (e: Exception) {
                failures.add(e.message?.take(90) ?: "network error")
            }
        }
        return "I couldn't retrieve parseable web results after trying all public search providers. " +
            "Details: " + failures.joinToString("; ") + ". No AI quota was used; try again or open a browser search."
    }

    private fun decodeHtml(value: String): String = value
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'")
        .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&nbsp;", " ").replace("&#x2F;", "/")
}
