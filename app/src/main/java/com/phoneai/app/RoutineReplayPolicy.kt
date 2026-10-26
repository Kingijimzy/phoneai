package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject

/** Pure parsing and recovery decisions for locally recorded gesture routines. */
internal object RoutineReplayPolicy {
    enum class Action { RUN, RESUME }
    data class Request(val action: Action, val name: String)

    fun parseRequest(command: String): Request? {
        val match = Regex("(?i)^\\s*(?:please\\s+)?(run|replay|play|execute|resume)\\s+(.+?)\\s*[.!]?\\s*$")
            .matchEntire(command) ?: return null
        val action = if (match.groupValues[1].equals("resume", true)) Action.RESUME else Action.RUN
        var name = match.groupValues[2].trim()
        val prefix = Regex("(?i)^(?:(?:the\\s+)?(?:saved\\s+)?(?:routine|macro)\\s+)")
        name = name.replaceFirst(prefix, "").trim().trim('"', '\'', ' ', '.', '!')
        if (name.isBlank() || name.equals("routine", true) || name.equals("macro", true)) return null
        return Request(action, name)
    }

    /** Never use fuzzy names for actions: the routine name must match the user's saved name. */
    fun exactName(names: List<String>, requested: String): String? {
        val wanted = MacroStore.norm(requested)
        if (wanted.isBlank()) return null
        return names.firstOrNull { MacroStore.norm(it) == wanted }
    }

    fun signature(step: JSONObject, key: String): IntArray? {
        val a = step.optJSONArray(key) ?: return null
        if (a.length() != 108) return null
        val out = IntArray(a.length())
        for (i in 0 until a.length()) {
            val v = a.optInt(i, -1)
            if (v !in 0..255) return null
            out[i] = v
        }
        return out
    }

    fun scaleCoordinate(value: Int, recordedSize: Int, currentSize: Int): Int? {
        if (recordedSize <= 0 || currentSize <= 0 || value < 0) return null
        val scaled = (value.toDouble() * currentSize / recordedSize).toInt()
        return scaled.takeIf { it in 0 until currentSize }
    }

    fun resumeDecision(beforeMatches: Boolean, afterMatches: Boolean): ResumeDecision = when {
        beforeMatches && !afterMatches -> ResumeDecision.RETRY_IN_FLIGHT
        afterMatches && !beforeMatches -> ResumeDecision.SKIP_IN_FLIGHT
        else -> ResumeDecision.AMBIGUOUS
    }

    enum class ResumeDecision { RETRY_IN_FLIGHT, SKIP_IN_FLIGHT, AMBIGUOUS }
}
