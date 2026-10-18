package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class Memory(val id: Int, var category: String, var text: String, val created: Long, var updated: Long)

/** Orion's long-term memory. Saved in a file on the phone, so it survives closing the app. */
object MemoryStore {
    val CATEGORIES = listOf("preference", "project", "decision", "task", "conversation", "fact")
    private val lock = Any()
    private var cache: MutableList<Memory>? = null

    private fun file(c: Context) = File(c.filesDir, "orion_memory.json")

    fun cat(s: String?): String {
        val v = (s ?: "").lowercase().trim()
        return if (v in CATEGORIES) v else "fact"
    }

    private fun load(c: Context): MutableList<Memory> {
        val existing = cache
        if (existing != null) return existing
        val out = ArrayList<Memory>()
        try {
            val f = file(c)
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out.add(
                        Memory(
                            o.getInt("id"), cat(o.optString("category", "fact")), o.optString("text", ""),
                            o.optLong("created", 0L), o.optLong("updated", 0L)
                        )
                    )
                }
            }
        } catch (e: Exception) {
        }
        cache = out
        return out
    }

    private fun save(c: Context) {
        val list = cache ?: return
        val arr = JSONArray()
        for (m in list) {
            arr.put(
                JSONObject().put("id", m.id).put("category", m.category).put("text", m.text)
                    .put("created", m.created).put("updated", m.updated)
            )
        }
        try {
            file(c).writeText(arr.toString())
        } catch (e: Exception) {
        }
    }

    fun all(c: Context): List<Memory> = synchronized(lock) { load(c).toList() }

    fun add(c: Context, category: String?, text: String): Memory? = synchronized(lock) {
        val t = text.trim().replace("\n", " ").take(300)
        if (t.isEmpty()) return@synchronized null
        val list = load(c)
        val now = System.currentTimeMillis()
        val dup = list.firstOrNull { it.text.equals(t, true) }
        if (dup != null) {
            dup.updated = now
            if (category != null) dup.category = cat(category)
            save(c)
            return@synchronized dup
        }
        val id = (list.maxOfOrNull { it.id } ?: 0) + 1
        val m = Memory(id, cat(category), t, now, now)
        list.add(m)
        while (list.size > 300) list.removeAt(0)
        save(c)
        m
    }

    fun update(c: Context, id: Int, category: String?, text: String?): Boolean = synchronized(lock) {
        val m = load(c).firstOrNull { it.id == id } ?: return@synchronized false
        if (text != null) {
            val t = text.trim().replace("\n", " ").take(300)
            if (t.isNotEmpty()) m.text = t
        }
        if (category != null) m.category = cat(category)
        m.updated = System.currentTimeMillis()
        save(c)
        true
    }

    fun delete(c: Context, id: Int): Boolean = synchronized(lock) {
        val ok = load(c).removeAll { it.id == id }
        if (ok) save(c)
        ok
    }

    fun clear(c: Context) = synchronized(lock) {
        load(c).clear()
        save(c)
    }

    fun search(c: Context, query: String): List<Memory> {
        val words = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }
        if (words.isEmpty()) return all(c).takeLast(10)
        return all(c).filter { m -> words.any { m.text.lowercase().contains(it) || m.category.contains(it) } }
    }

    /** Text added to the AI's instructions so it "remembers". */
    fun promptBlock(c: Context): String {
        val items = all(c).sortedBy { it.updated }.takeLast(80)
        if (items.isEmpty()) return "(nothing saved yet)"
        return items.joinToString("\n") { "[${it.id}] (${it.category}) ${it.text}" }
    }
}
