package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Bounds text-only model requests before they reach a provider.
 *
 * A command can accumulate large tool results and screen descriptions over several
 * planning turns. Keep the system instruction plus the newest useful turns, trimming
 * oversized content rather than sending a request likely to fail with HTTP 413.
 */
internal object RequestPayloadPolicy {
    const val MAX_TOTAL_CHARS = 18000
    const val MAX_SYSTEM_CHARS = 8500
    const val MAX_MESSAGE_CHARS = 3500
    private const val MAX_RECENT_MESSAGES = 7
    private const val TRUNCATION_NOTE = "\n[Earlier content trimmed to keep this request within Orion's size limit.]"

    fun bound(messages: JSONArray, maxTotalChars: Int = MAX_TOTAL_CHARS): JSONArray {
        if (messages.length() == 0) return JSONArray()
        val safeTotal = maxTotalChars.coerceAtLeast(2000)
        val source = (0 until messages.length()).mapNotNull { index ->
            messages.optJSONObject(index)?.let { JSONObject(it.toString()) }
        }
        if (source.isEmpty()) return JSONArray()

        val system = source.firstOrNull { it.optString("role") == "system" }
        val recent = source.filter { it !== system }.takeLast(MAX_RECENT_MESSAGES)
        val output = JSONArray()
        var used = 0

        if (system != null) {
            val content = system.optString("content", "")
            val cap = minOf(MAX_SYSTEM_CHARS, safeTotal / 2)
            val bounded = if (content.length > cap) content.take(cap - TRUNCATION_NOTE.length) + TRUNCATION_NOTE else content
            system.put("content", bounded)
            output.put(system)
            used += bounded.length
        }

        val selected = ArrayList<JSONObject>()
        for (message in recent.asReversed()) {
            val role = message.optString("role")
            val original = message.optString("content", "")
            val remaining = safeTotal - used
            if (remaining < 256) break
            val perMessageCap = minOf(MAX_MESSAGE_CHARS, remaining)
            val bounded = if (original.length > perMessageCap) {
                original.take((perMessageCap - TRUNCATION_NOTE.length).coerceAtLeast(0)) + TRUNCATION_NOTE
            } else original
            if (bounded.isBlank() && role != "user") continue
            message.put("content", bounded)
            selected.add(message)
            used += bounded.length
        }
        selected.asReversed().forEach { output.put(it) }
        return output
    }
}
