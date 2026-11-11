package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Model-led routing: one small structured judgment about what a request needs, made from the conversation,
 * the unfinished work and what Orion can actually do right now, not from words in the sentence.
 * If the provider times out, fails or returns something unusable, [fallback] supplies the old structural
 * rules so Orion keeps working (provider resilience); [Decision.source] records which one decided.
 */
internal object OrionRouter {
    enum class Kind { CHAT, ACT, PLAN, CONTINUE, LOOKUP }
    data class Decision(val kind: Kind, val plan: Boolean, val sequential: Boolean, val source: String) {
        val needsTools: Boolean get() = kind != Kind.CHAT
    }

    const val SYSTEM = "You are Orion's router. Decide what the owner's latest message needs, from meaning and context, not from keywords. " +
        "Reply with ONLY JSON: {\"route\":\"chat|act|plan|continue|lookup\",\"plan\":bool,\"sequential\":bool}. " +
        "chat = conversation, opinion or knowledge you can answer yourself. act = something must be done or fetched with tools (phone, files, code, web, accounts, memory writes). " +
        "plan = several dependent objectives that need an ordered objective graph. continue = refers to unfinished or previous work (\"do it\", \"the other one\", \"finish that\", a correction). " +
        "lookup = asks what was said or done in an earlier conversation. plan=true only for plan; sequential=true only when order matters. " +
        "Use the unfinished objective and recent turns to resolve references. Only choose act if the tools listed as available can plausibly do it."

    /** Pure parse: tolerant of fences and prose around the JSON. Null when unusable. */
    fun parse(raw: String): Decision? {
        val a = raw.indexOf('{'); val b = raw.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        val o = runCatching { JSONObject(raw.substring(a, b + 1)) }.getOrNull() ?: return null
        val kind = when (o.optString("route").trim().lowercase()) {
            "chat" -> Kind.CHAT; "act" -> Kind.ACT; "plan" -> Kind.PLAN
            "continue" -> Kind.CONTINUE; "lookup" -> Kind.LOOKUP
            else -> return null
        }
        return Decision(kind, kind == Kind.PLAN || o.optBoolean("plan", false), o.optBoolean("sequential", false), "model")
    }

    /** Structural last resort, used only when no model answer is available. */
    fun fallback(command: String): Decision {
        val plan = OrionCommandIntentPolicy.requiresStructuredTaskPlanning(command)
        val act = plan || OrionCommandIntentPolicy.requiresDirectActionRouting(command) || ConversationAutoRoutePolicy.requiresToolAgent(command)
        val seq = Regex("(?i)\\b(?:depends on one another|in that order|sequential(?:ly)?)\\b").containsMatchIn(command)
        return Decision(if (plan) Kind.PLAN else if (act) Kind.ACT else Kind.CHAT, plan, seq, "fallback")
    }

    fun userMessage(command: String, recent: List<Pair<String, String>>, objective: String, capabilities: String): String = buildString {
        if (capabilities.isNotBlank()) appendLine("LIVE ENVIRONMENT AND CAPABILITY CONTEXT:\n" + capabilities)
        if (objective.isNotBlank()) appendLine("ACTIVE / UNFINISHED OBJECTIVE:\n" + objective)
        if (recent.isNotEmpty()) {
            appendLine("RECENT TURNS:")
            recent.forEach { (r, t) -> appendLine("$r: $t") }
        }
        append("LATEST MESSAGE: ").append(command)
    }

    fun toMessages(system: String, user: String): JSONArray =
        JSONArray().put(JSONObject().put("role", "system").put("content", system)).put(JSONObject().put("role", "user").put("content", user))
}
