package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject

/** Bounded context reduction for provider HTTP 413 responses. */
internal object OrionProviderPayloadRecovery {
    fun compact(messages: JSONArray, pass: Int): JSONArray {
        require(pass in 1..2) { "Payload recovery is limited to two compaction passes." }
        val result = JSONArray()
        val systemMessages = mutableListOf<JSONObject>()
        val conversational = mutableListOf<JSONObject>()
        for (index in 0 until messages.length()) {
            val item = messages.optJSONObject(index) ?: continue
            val role = item.optString("role").lowercase()
            if (role == "system" || role == "developer") systemMessages += JSONObject(item.toString())
            else conversational += JSONObject(item.toString())
        }
        systemMessages.forEach(result::put)
        val keep = if (pass == 1) 8 else 4
        val latestUser = conversational.indexOfLast { it.optString("role").equals("user", true) }
        if (latestUser >= 0) {
            // The current user request is a hard keep, even when it is older than the recent tail.
            result.put(conversational[latestUser])
            var start = maxOf(latestUser + 1, conversational.size - keep)
            // Avoid starting with an orphaned tool output when its assistant tool-call was trimmed.
            while (start < conversational.size && conversational[start].optString("role").equals("tool", true)) start++
            val limit = if (pass == 1) 2_000 else 900
            for (index in start until conversational.size) {
                val item = conversational[index]
                val role = item.optString("role").lowercase()
                val raw = item.opt("content")
                if ((role == "tool" || role == "assistant") && raw is String && raw.length > limit) {
                    val head = raw.take(limit * 2 / 3)
                    val tail = raw.takeLast(limit / 3)
                    item.put("content", "$head\n[…older tool output shortened for request-size recovery…]\n$tail")
                }
                result.put(item)
            }
        } else {
            conversational.takeLast(keep).forEach(result::put)
        }
        return result
    }
}
