package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Bounded MCP pagination. Server cursors are opaque untrusted data: validate their size,
 * reject control characters and cycles, and cap both pages and accumulated items.
 */
internal object OrionMcpPagination {
    data class Result(val items: JSONArray, val pages: Int, val truncated: Boolean)

    fun collect(
        itemsKey: String,
        firstCursor: String? = null,
        maxPages: Int = 5,
        maxItems: Int = 100,
        fetchPage: (String?) -> JSONObject
    ): Result {
        require(itemsKey in setOf("resources", "prompts", "tools")) { "Unsupported MCP page collection." }
        require(maxPages in 1..10) { "Invalid MCP page limit." }
        require(maxItems in 1..200) { "Invalid MCP item limit." }
        fun validCursor(value: String?): String? {
            if (value.isNullOrBlank()) return null
            require(value.length <= 2_000 && value.none { it.isISOControl() }) {
                "MCP server returned an invalid pagination cursor."
            }
            return value
        }

        var cursor = validCursor(firstCursor)
        val visited = mutableSetOf<String>()
        val items = JSONArray()
        var pages = 0
        var truncated = false
        while (pages < maxPages && items.length() < maxItems) {
            if (cursor != null) require(visited.add(cursor)) { "MCP pagination cursor cycle detected." }
            val page = fetchPage(cursor)
            pages += 1
            val rows = page.optJSONArray(itemsKey) ?: JSONArray()
            for (index in 0 until rows.length()) {
                if (items.length() >= maxItems) {
                    truncated = true
                    break
                }
                rows.optJSONObject(index)?.let { items.put(it) }
            }
            val next = validCursor(page.optString("nextCursor").takeIf { page.has("nextCursor") && !page.isNull("nextCursor") })
            if (next == null) break
            if (next in visited) throw IllegalStateException("MCP pagination cursor cycle detected.")
            if (items.length() >= maxItems || pages >= maxPages) {
                truncated = true
                break
            }
            cursor = next
        }
        return Result(items, pages, truncated)
    }
}
