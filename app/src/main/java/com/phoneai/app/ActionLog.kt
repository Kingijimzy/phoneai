package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Minimal privacy-preserving action history: tool names and outcome only, never arguments or screen text. */
data class OrionActionEvent(val at: Long, val tool: String, val outcome: String)

object ActionLogStore {
    private val lock = Any()
    private var cache: MutableList<OrionActionEvent>? = null
    private fun file(c: Context) = File(c.filesDir, "orion_action_log.json")

    private fun load(c: Context): MutableList<OrionActionEvent> {
        cache?.let { return it }
        val items = mutableListOf<OrionActionEvent>()
        try {
            val f = file(c)
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    items.add(OrionActionEvent(
                        o.optLong("at", 0L),
                        o.optString("tool", "unknown").take(48),
                        o.optString("outcome", "result returned").take(32)
                    ))
                }
            }
        } catch (_: Exception) { }
        cache = items
        return items
    }

    private fun save(c: Context) {
        val arr = JSONArray()
        load(c).forEach { e ->
            arr.put(JSONObject().put("at", e.at).put("tool", e.tool).put("outcome", e.outcome))
        }
        try {
            val target = file(c)
            val temp = File(c.filesDir, "orion_action_log.json.tmp")
            temp.writeText(arr.toString())
            if (!temp.renameTo(target)) {
                target.writeText(arr.toString())
                temp.delete()
            }
        } catch (_: Exception) { }
    }

    fun record(c: Context, tool: String, result: String) = synchronized(lock) {
        val outcome = if (result.trimStart().startsWith("error:", true)) "error returned" else "result returned"
        val items = load(c)
        items.add(OrionActionEvent(System.currentTimeMillis(), tool.take(48), outcome))
        while (items.size > 200) items.removeAt(0)
        save(c)
    }

    fun all(c: Context): List<OrionActionEvent> = synchronized(lock) { load(c).toList().reversed() }

    fun clear(c: Context) = synchronized(lock) {
        load(c).clear()
        save(c)
    }
}
