package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject

/** Reassembles OpenAI-style streamed tool_call fragments (index, id, name, argument pieces) into whole calls. */
internal class OrionSseToolAccumulator {
    private class Slot { var id = ""; var name = StringBuilder(); var args = StringBuilder() }
    private val slots = java.util.TreeMap<Int, Slot>()

    fun feed(toolCallDeltas: JSONArray?) {
        if (toolCallDeltas == null) return
        for (i in 0 until toolCallDeltas.length()) {
            val d = toolCallDeltas.optJSONObject(i) ?: continue
            val slot = slots.getOrPut(d.optInt("index", i)) { Slot() }
            d.optString("id").takeIf { it.isNotBlank() && it != "null" }?.let { slot.id = it }
            val fn = d.optJSONObject("function") ?: continue
            fn.optString("name").takeIf { it.isNotEmpty() && it != "null" }?.let { slot.name.append(it) }
            fn.optString("arguments").takeIf { it.isNotEmpty() && it != "null" }?.let { slot.args.append(it) }
        }
    }

    fun hasCalls(): Boolean = slots.values.any { it.name.isNotEmpty() }

    fun toJson(): JSONArray {
        val out = JSONArray()
        var n = 0
        for (s in slots.values) {
            if (s.name.isEmpty()) continue
            out.put(JSONObject()
                .put("id", s.id.ifBlank { "call_stream_${n}" })
                .put("type", "function")
                .put("function", JSONObject().put("name", s.name.toString()).put("arguments", s.args.toString().ifBlank { "{}" })))
            n++
        }
        return out
    }
}
