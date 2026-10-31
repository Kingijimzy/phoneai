package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Versioned backup of the trading desk: playbook, practice account, watchlists and the trade journal.
 * Never contains API keys or tokens, and never restores safety state (kill switch, loss streak, daily counters),
 * so a backup file cannot switch protections off.
 */
internal object DeskBackup {
    const val VERSION = 1
    val FILES = setOf("trading_desk", "chain_desk")
    private val skipKeys = setOf("kill", "loss_streak", "trades_today", "last_loss", "day", "last_sync", "brief_day", "habit_day")
    private val secretWords = listOf("key", "token", "secret", "password", "seed", "mnemonic")

    fun allowed(key: String): Boolean = key !in skipKeys && secretWords.none { key.lowercase().contains(it) }

    class Restored(val prefs: Map<String, Map<String, Any>>, val journal: JSONArray)

    fun build(prefs: Map<String, Map<String, Any?>>, journal: JSONArray, now: Long): String {
        val files = JSONObject()
        for ((file, map) in prefs) {
            if (file !in FILES) continue
            val o = JSONObject()
            for ((k, v) in map) {
                if (!allowed(k)) continue
                val e = when (v) {
                    is String -> JSONObject().put("t", "s").put("v", v)
                    is Boolean -> JSONObject().put("t", "b").put("v", v)
                    is Int -> JSONObject().put("t", "i").put("v", v)
                    is Long -> JSONObject().put("t", "l").put("v", v)
                    is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                    else -> continue
                }
                o.put(k, e)
            }
            files.put(file, o)
        }
        return JSONObject().put("app", "orion-desk").put("v", VERSION).put("createdAt", now)
            .put("prefs", files).put("journal", journal).toString(2)
    }

    /** Throws IllegalArgumentException with a readable message when the file is not a usable backup. */
    fun parse(text: String): Restored {
        if (text.length > 20 * 1024 * 1024) throw IllegalArgumentException("That file is too large to be a desk backup.")
        val root = try { JSONObject(text) } catch (_: Exception) { throw IllegalArgumentException("That file is not a desk backup (not valid JSON).") }
        if (root.optString("app") != "orion-desk") throw IllegalArgumentException("That file is not an Orion desk backup.")
        val v = root.optInt("v", 0)
        if (v < 1) throw IllegalArgumentException("Backup has no version number.")
        if (v > VERSION) throw IllegalArgumentException("This backup was made by a newer version of Orion. Update the app first.")
        val out = LinkedHashMap<String, Map<String, Any>>()
        val files = root.optJSONObject("prefs") ?: JSONObject()
        for (file in files.keys()) {
            if (file !in FILES) continue
            val o = files.getJSONObject(file)
            val m = LinkedHashMap<String, Any>()
            for (k in o.keys()) {
                if (!allowed(k)) continue
                val e = o.optJSONObject(k) ?: continue
                val value: Any = when (e.optString("t")) {
                    "s" -> e.optString("v")
                    "b" -> e.optBoolean("v")
                    "i" -> e.optInt("v")
                    "l" -> e.optLong("v")
                    "f" -> e.optDouble("v").toFloat()
                    else -> continue
                }
                m[k] = value
            }
            out[file] = m
        }
        return Restored(out, root.optJSONArray("journal") ?: JSONArray())
    }

    fun summary(r: Restored): String =
        "${r.prefs.values.sumOf { it.size }} desk settings, ${r.journal.length()} journal trades"
}
