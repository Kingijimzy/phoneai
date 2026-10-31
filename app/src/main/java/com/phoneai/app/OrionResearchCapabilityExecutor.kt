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
        // Both public-search tool names must return actual search results, not merely open a
        // browser URL. The old web_search route returned an "opened" status that the planner
        // could mistake for research failure and retry indefinitely.
        "web_search", "live_web_search" -> {
            val query = args.optString("query").trim()
            if (query.isBlank()) "error: search query is required."
            else {
                val result = LiveWebSearch.search(query)
                if (result.startsWith("I couldn't retrieve parseable web results", ignoreCase = true))
                    "error: public web search failed: " + result
                else result
            }
        }
        "live_news" -> LiveNews.search(args.optString("query"))
        "fetch_url", "research_web" -> {
            val result = BrowserResearch.fetch(args.optString("url"), cancelled)
            val page = result.page
            if (page == null) "error: " + (result.error ?: "could not read webpage")
            else "SOURCE: " + page.sourceUrl + "\nTITLE: " + page.title +
                "\nUNTRUSTED WEBPAGE CONTENT:\n" + page.text.take(3500)
        }
        "weather" -> WeatherService.current(args.optString("location"))
        "live_sports" -> LiveSports.answer(args.optString("query"))
            ?: "error: no verified scoreboard data was available for this request."
        "github_search_repositories" -> tools.githubSearchRepository(args.optString("query"))
        "github_public" -> tools.githubPublic(
            args.optString("owner"),
            args.optString("repo"),
            args.optString("section", "repo"),
            args.optString("run_id").takeIf { it.isNotBlank() },
            args.optString("branch", "main"),
            args.optString("path")
        )
        "bybit_public_ticker" -> tools.bybitPublicTicker(args.optString("symbol"))
        else -> "error: research executor does not implement '$tool'."
    }
}
