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

    /** Atomic replacement where supported; return failure instead of silently claiming data was saved. */
    private fun save(c: Context): Boolean {
        val list = cache ?: return false
        val arr = JSONArray()
        for (m in list) {
            arr.put(
                JSONObject().put("id", m.id).put("category", m.category).put("text", m.text)
                    .put("created", m.created).put("updated", m.updated)
            )
        }
        val target = file(c)
        val temp = File(c.filesDir, "orion_memory.json.tmp")
        return try {
            temp.writeText(arr.toString())
            if (temp.renameTo(target)) {
                true
            } else {
                // Fallback for filesystems where rename-over-existing is unsupported.
                target.writeText(arr.toString())
                temp.delete()
                true
            }
        } catch (_: Exception) {
            temp.delete()
            false
        }
    }

    private fun copyOf(m: Memory) = Memory(m.id, m.category, m.text, m.created, m.updated)

    fun all(c: Context): List<Memory> = synchronized(lock) { load(c).map { copyOf(it) } }

    fun add(c: Context, category: String?, text: String): Memory? = synchronized(lock) {
        val t = text.trim().replace("\n", " ").take(300)
        if (t.isEmpty()) return@synchronized null
        val list = load(c)
        val now = System.currentTimeMillis()
        val dup = list.firstOrNull { it.text.equals(t, true) }
        if (dup != null) {
            val oldCategory = dup.category
            val oldUpdated = dup.updated
            dup.updated = now
            if (category != null) dup.category = cat(category)
            if (!save(c)) {
                dup.category = oldCategory
                dup.updated = oldUpdated
                return@synchronized null
            }
            return@synchronized copyOf(dup)
        }
        val id = (list.maxOfOrNull { it.id } ?: 0) + 1
        val m = Memory(id, cat(category), t, now, now)
        list.add(m)
        val removed = mutableListOf<Memory>()
        while (list.size > 300) removed.add(list.removeAt(0))
        if (!save(c)) {
            list.removeAll { it.id == id }
            list.addAll(0, removed)
            return@synchronized null
        }
        copyOf(m)
    }

    fun update(c: Context, id: Int, category: String?, text: String?): Boolean = synchronized(lock) {
        val m = load(c).firstOrNull { it.id == id } ?: return@synchronized false
        val oldText = m.text
        val oldCategory = m.category
        val oldUpdated = m.updated
        if (text != null) {
            val t = text.trim().replace("\n", " ").take(300)
            if (t.isNotEmpty()) m.text = t
        }
        if (category != null) m.category = cat(category)
        m.updated = System.currentTimeMillis()
        if (!save(c)) {
            m.text = oldText
            m.category = oldCategory
            m.updated = oldUpdated
            return@synchronized false
        }
        true
    }

    fun delete(c: Context, id: Int): Boolean = synchronized(lock) {
        val list = load(c)
        val removed = list.filter { it.id == id }
        if (removed.isEmpty()) return@synchronized false
        list.removeAll { it.id == id }
        if (!save(c)) {
            list.addAll(removed)
            return@synchronized false
        }
        true
    }

    fun clear(c: Context): Boolean = synchronized(lock) {
        val list = load(c)
        val previous = list.map { copyOf(it) }
        list.clear()
        if (!save(c)) {
            list.addAll(previous)
            return@synchronized false
        }
        true
    }

    /** Rank relevant memories first, rather than returning the first loose substring match. */
    fun search(c: Context, query: String): List<Memory> {
        val normalized = query.trim().lowercase()
        val words = normalized.split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.distinct()
        val memories = all(c)
        if (words.isEmpty()) return memories.sortedByDescending { it.updated }.take(10)
        return memories.mapNotNull { m ->
            val body = m.text.lowercase()
            val category = m.category.lowercase()
            val matched = words.count { body.contains(it) || category.contains(it) }
            if (matched == 0) null else {
                val score = matched * 10 +
                    (if (body.contains(normalized)) 20 else 0) +
                    (if (category == normalized) 5 else 0)
                Triple(m, score, m.updated)
            }
        }.sortedWith(compareByDescending<Triple<Memory, Int, Long>> { it.second }
            .thenByDescending { it.third })
            .map { it.first }
    }

    /** Text added to the AI's instructions so it "remembers". */
    fun promptBlock(c: Context): String {
        val items = all(c).sortedBy { it.updated }.takeLast(25)
        if (items.isEmpty()) return "(nothing saved yet)"
        return items.joinToString("\n") { "[${it.id}] (${it.category}) ${it.text}" }
    }
}
