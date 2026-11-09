package com.phoneai.app

import org.json.JSONObject

/** Parses the model's one-JSON-object-per-step reply and detects truncated or damaged output. */
internal object AgentJson {
    class Parsed(val obj: JSONObject, val damaged: Boolean)

    fun parse(raw: String): Parsed {
        // Some providers leak their private tool-call serialization as ordinary assistant text.
        // Never accept the embedded "{}" as a valid empty plan or show raw control markup to users.
        if (raw.contains("DSML") || raw.contains("tool_call>")) {
            OrionToolCallRecovery.recover(raw) { ToolRegistry.find(it) != null }?.let { return Parsed(it, false) }
        }
        if (raw.contains("<|tool_call>") || raw.contains("</|tool_call>") ||
            raw.contains("<|tool_call|>") || raw.contains("</tool_call>")) {
            return Parsed(JSONObject(), true)
        }
        if (!raw.trimStart().startsWith("{") && !raw.contains("\"tool\"")) {
            OrionToolCallRecovery.recover(raw) { ToolRegistry.find(it) != null }?.let { return Parsed(it, false) }
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

/**
 * Some models answer with a pseudo tool call written as prose — `python_exec(code="...")` — instead of the
 * JSON step. Rather than showing that text to Kingi as if it were an answer, turn it into the real step.
 */
internal object OrionToolCallRecovery {
    /** DeepSeek-style `<｜｜DSML｜｜ invoke name="x"><｜｜DSML｜｜ parameter name="k" ...>v</...parameter>` blocks. */
    private fun recoverDsml(raw: String, isKnownTool: (String) -> Boolean): JSONObject? {
        if (!raw.contains("DSML")) return null
        val text = raw.replace('｜', '|')
        val inv = Regex("invoke\\s+name=\"([A-Za-z0-9_]+)\"").find(text) ?: return null
        val tool = inv.groupValues[1]
        if (!isKnownTool(tool)) return null
        val args = JSONObject()
        Regex("parameter\\s+name=\"([A-Za-z0-9_]+)\"[^>]*>(.*?)</\\|*DSML\\|*\\s*parameter>", RegexOption.DOT_MATCHES_ALL)
            .findAll(text.substring(inv.range.last)).forEach { args.put(it.groupValues[1], it.groupValues[2].trim('\n', '\r')) }
        val say = text.substring(0, text.indexOf("<|").takeIf { it > 0 } ?: 0).trim().take(240)
        return JSONObject().put("say", say).put("tool", tool).put("args", args)
    }

    fun recover(raw: String, isKnownTool: (String) -> Boolean): JSONObject? {
        recoverDsml(raw, isKnownTool)?.let { return it }
        val call = Regex("\\b([a-z][a-z0-9_]{2,40})\\(\\s*([A-Za-z_]\\w*)\\s*=").findAll(raw)
            .firstOrNull { isKnownTool(it.groupValues[1]) } ?: return null
        val tool = call.groupValues[1]
        var i = call.range.first + tool.length + 1
        val args = JSONObject()
        while (i < raw.length) {
            val key = Regex("\\G\\s*,?\\s*([A-Za-z_]\\w*)\\s*=\\s*").find(raw, i) ?: break
            i = key.range.last + 1
            val q = when {
                raw.startsWith("\"\"\"", i) -> "\"\"\""
                i < raw.length && (raw[i] == '"' || raw[i] == '\'') -> raw[i].toString()
                else -> {
                    val end = Regex("[,)]").find(raw, i)?.range?.first ?: raw.length
                    args.put(key.groupValues[1], raw.substring(i, end).trim()); i = end; continue
                }
            }
            val start = i + q.length
            val nextArg = Regex(Regex.escape(q) + "\\s*,\\s*[A-Za-z_]\\w*\\s*=").find(raw, start)
            val end = nextArg?.range?.first
                ?: raw.lastIndexOf(q + ")").takeIf { it >= start }
                ?: raw.lastIndexOf(q).takeIf { it >= start }
                ?: raw.length
            args.put(key.groupValues[1], raw.substring(start, end))
            i = end + q.length
        }
        if (args.length() == 0) return null
        val say = raw.substring(0, call.range.first).trim().lines().filter { it.isNotBlank() }.take(2).joinToString(" ").take(240)
        return JSONObject().put("say", say).put("tool", tool).put("args", args)
    }
}
