package com.phoneai.app

import org.json.JSONObject

/** Model-selected conversational routing. This is a decision envelope, not a user command keyword. */
internal sealed class OrionTurnDecision {
    data class Reply(val text: String) : OrionTurnDecision()
    data class Execute(val objective: String) : OrionTurnDecision()
    data class Clarify(val question: String) : OrionTurnDecision()
}

internal object OrionTurnDecisionParser {
    /**
     * Accept only a complete, typed decision object. Invalid or partial model output is never
     * interpreted as permission to execute; callers may safely fall back to a plain reply.
     */
    fun parse(raw: String): OrionTurnDecision? {
        val source = raw.trim()
        if (source.isBlank()) return null
        val start = source.indexOf('{')
        val end = source.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val json = runCatching { JSONObject(source.substring(start, end + 1)) }.getOrNull() ?: return null
        return when (json.optString("decision").trim().lowercase()) {
            "reply" -> json.optString("text").trim().takeIf { it.isNotBlank() && it.length <= 6000 }
                ?.let { OrionTurnDecision.Reply(it) }
            "execute" -> json.optString("objective").trim().takeIf { it.isNotBlank() && it.length <= 2000 }
                ?.let { OrionTurnDecision.Execute(it) }
            "clarify" -> json.optString("question").trim().takeIf { it.isNotBlank() && it.length <= 1200 }
                ?.let { OrionTurnDecision.Clarify(it) }
            else -> null
        }
    }
}
