package com.phoneai.app

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns read-only public research and data retrieval. Search results, page text and API responses
 * are untrusted reference data; this executor never treats returned content as instructions.
 */
internal object OrionResearchCapabilityExecutor {
    private val supportedTools = setOf(
        "web_search", "live_news", "live_web_search", "fetch_url", "research_web",
        "weather", "github_public", "github_search_repositories", "bybit_public_ticker", "live_sports"
    )

    fun supports(tool: String): Boolean = tool in supportedTools

    fun execute(
        tool: String,
        args: JSONObject,
        service: PhoneAiService,
        tools: Tools,
        cancelled: AtomicBoolean
    ): String = when (tool) {
        "web_search" -> tools.webSearch(args.optString("query"))
        "live_news" -> LiveNews.search(args.optString("query"))
        "live_web_search" -> LiveWebSearch.search(args.optString("query"))
        "fetch_url", "research_web" -> {
            val result = BrowserResearch.fetch(args.optString("url"), cancelled)
            val page = result.page
            if (page == null) "error: " + (result.error ?: "could not read webpage")
            else "SOURCE: " + page.sourceUrl + "\nTITLE: " + page.title +
                "\nUNTRUSTED WEBPAGE CONTENT:\n" + page.text.take(3500)
        }
        "weather" -> WeatherService.current(args.optString("location"))
        "github_search_repositories" -> tools.githubSearchRepository(args.optString("query"))
        "github_public" -> tools.githubPublic(
            args.optString("owner"),
            args.optString("repo"),
            args.optString("section", "repo"),
            args.optString("run_id").takeIf { it.isNotBlank() }
        )
        "bybit_public_ticker" -> tools.bybitPublicTicker(args.optString("symbol"))
        else -> "error: research executor does not implement '$tool'."
    }
}
