package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Local undo journal for narrowly supported memory changes.
 * Each entry stores only the specific memory snapshot needed to reverse that change.
 */
object UndoStore {
    private const val LIMIT = 20
    private val lock = Any()
    private fun file(c: Context) = File(c.filesDir, "orion_undo.json")

    private fun encode(m: Memory?): JSONObject = if (m == null) JSONObject().put("none", true) else JSONObject()
        .put("id", m.id).put("category", m.category).put("text", m.text)
        .put("created", m.created).put("updated", m.updated)
        .put("sourceRole", m.sourceRole).put("confidence", m.confidence)
        .put("sensitivity", m.sensitivity).put("reviewStatus", m.reviewStatus).put("evidence", m.evidence)

    private fun decode(o: JSONObject?): Memory? {
        if (o == null || o.optBoolean("none", false)) return null
        if (!o.has("id") || !o.has("text")) return null
        return Memory(o.optInt("id", -1), o.optString("category", "fact"),
            o.optString("text", ""), o.optLong("created", 0L), o.optLong("updated", 0L),
            false, o.optString("sourceRole", "legacy"), o.optDouble("confidence", 0.5),
            o.optString("sensitivity", "general"), o.optString("reviewStatus", "pending"), o.optString("evidence", ""))
    }

    fun recordMemory(c: Context, operation: String, before: Memory?, after: Memory?) = synchronized(lock) {
        val entries = load(c)
        entries.put(JSONObject().put("operation", operation).put("before", encode(before))
            .put("after", encode(after)).put("at", System.currentTimeMillis()))
        while (entries.length() > LIMIT) entries.remove(0)
        save(c, entries)
    }

    fun status(c: Context): String = synchronized(lock) {
        val entries = load(c)
        if (entries.length() == 0) "Nothing reversible has been recorded yet."
        else {
            val e = entries.optJSONObject(entries.length() - 1) ?: return@synchronized "Undo history is unreadable."
            "Last reversible change: ${e.optString("operation", "memory change").replace('_', ' ')}. " +
                "Say ‘undo that’ to reverse it, if the saved state has not changed."
        }
    }

    fun undoLast(c: Context): String = synchronized(lock) {
        val entries = load(c)
        if (entries.length() == 0) return@synchronized "Nothing to undo yet. Orion currently supports undo for saved-memory changes only."
        val e = entries.optJSONObject(entries.length() - 1) ?: return@synchronized "Undo history is unreadable; nothing was changed."
        val before = decode(e.optJSONObject("before"))
        val after = decode(e.optJSONObject("after"))
        val operation = e.optString("operation", "")
        val memories = MemoryStore.all(c)
        val current = (after ?: before)?.let { target -> memories.firstOrNull { it.id == target.id } }
        val ok = when (operation) {
            "memory_add" -> {
                val expected = after
                val actual = current
                if (expected != null && actual != null && actual.text == expected.text && actual.category == expected.category)
                    MemoryStore.delete(c, expected.id) else false
            }
            "memory_update" -> {
                val expected = after
                val prior = before
                val actual = current
                if (expected != null && prior != null && actual != null &&
                    actual.text == expected.text && actual.category == expected.category)
                    MemoryStore.update(c, prior.id, prior.category, prior.text) else false
            }
            "memory_delete" -> {
                val prior = before
                if (prior != null && memories.none { it.id == prior.id })
                    MemoryStore.restore(c, prior.id, prior.category, prior.text, prior.created, prior.updated, prior.sourceRole, prior.confidence, prior.sensitivity, prior.reviewStatus, prior.evidence) else false
            }
            else -> false
        }
        if (!ok) return@synchronized "Could not safely undo this change because the saved memory may have changed since then. No data was overwritten."
        entries.remove(entries.length() - 1)
        save(c, entries)
        "Undid the last saved-memory change successfully."
    }

    private fun load(c: Context): JSONArray = try {
        val f = file(c)
        if (f.exists()) JSONArray(f.readText()) else JSONArray()
    } catch (_: Exception) { JSONArray() }

    private fun save(c: Context, entries: JSONArray) {
        val target = file(c)
        val temp = File(c.filesDir, "orion_undo.json.tmp")
        try {
            temp.writeText(entries.toString())
            if (!temp.renameTo(target)) {
                target.writeText(entries.toString())
                temp.delete()
            }
        } catch (_: Exception) {
            temp.delete()
        }
    }
}
