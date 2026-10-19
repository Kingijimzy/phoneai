package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Saved routines: a task Orion did once can be replayed later with NO AI at all. */
object MacroStore {
    private val lock = Any()

    private fun file(c: Context) = File(c.filesDir, "orion_routines.json")

    fun norm(s: String): String = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun load(c: Context): JSONObject = try {
        val f = file(c)
        if (f.exists()) JSONObject(f.readText()) else JSONObject()
    } catch (e: Exception) {
        JSONObject()
    }

    fun names(c: Context): List<String> = synchronized(lock) {
        val o = load(c)
        o.keys().asSequence().toList().sorted()
    }

    fun save(c: Context, name: String, steps: JSONArray): Boolean = synchronized(lock) {
        val n = norm(name)
        if (n.isEmpty() || steps.length() == 0) return@synchronized false
        val o = load(c)
        o.put(n, steps)
        try {
            file(c).writeText(o.toString())
            true
        } catch (e: Exception) {
            false
        }
    }

    fun delete(c: Context, name: String): Boolean = synchronized(lock) {
        val o = load(c)
        val key = o.keys().asSequence().firstOrNull { it == norm(name) } ?: return@synchronized false
        o.remove(key)
        try {
            file(c).writeText(o.toString())
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Finds a routine by (roughly) its name. Returns the name and its steps. */
    fun find(c: Context, name: String): Pair<String, JSONArray>? = synchronized(lock) {
        val n = norm(name)
        if (n.isEmpty()) return@synchronized null
        val o = load(c)
        val keys = o.keys().asSequence().toList()
        val key = keys.firstOrNull { it == n }
            ?: keys.firstOrNull { it.length >= 3 && n.contains(it) }
            ?: keys.firstOrNull { n.length >= 4 && it.contains(n) }
            ?: return@synchronized null
        Pair(key, o.getJSONArray(key))
    }
}
