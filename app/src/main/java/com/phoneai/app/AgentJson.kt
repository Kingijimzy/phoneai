package com.phoneai.app

import org.json.JSONObject

/** Parses the model's one-JSON-object-per-step reply and detects truncated or damaged output. */
internal object AgentJson {
    class Parsed(val obj: JSONObject, val damaged: Boolean)

    fun parse(raw: String): Parsed {
        // Some providers leak their private tool-call serialization as ordinary assistant text.
        // Never accept the embedded "{}" as a valid empty plan or show raw control markup to users.
        if (raw.contains("<|tool_call>") || raw.contains("</|tool_call>") ||
            raw.contains("<|tool_call|>") || raw.contains("</tool_call>")) {
            return Parsed(JSONObject(), true)
        }
        val s = raw.indexOf('{')
        if (s < 0) return Parsed(JSONObject().put("say", raw.trim()), false)
        val e = raw.lastIndexOf('}')
        if (e > s) {
            try { return Parsed(JSONObject(raw.substring(s, e + 1)), false) } catch (_: Exception) { }
        }
        // Looks like JSON but does not parse (usually cut off by a token limit). Never run or show it.
        return Parsed(JSONObject(), true)
    }

    /** Best-effort repair, used only to quote what the model was trying to say; never to execute a tool. */
    fun sayFromDamaged(raw: String): String =
        Regex("\"say\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(raw)?.groupValues?.get(1)
            ?.replace("\\n", "\n")?.replace("\\\"", "\"")?.trim().orEmpty()
}
