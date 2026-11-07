package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks follow-ups the owner mentioned in conversation so Orion can
 * surface them later without being reminded. Follow-ups are local-only,
 * bounded, and never sent anywhere.
 */
internal object OrionFollowUpStore {
    private const val PREFS = "orion_followups_v1"
    private const val KEY_ITEMS = "items"
    private const val MAX_ITEMS = 50

    data class FollowUp(
        val id: String,
        val text: String,
        val createdAt: Long,
        val dueAt: Long?,
        val done: Boolean = false,
        val mentionedAtTurn: Int = 0
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Add a follow-up the owner mentioned. Returns the stored id. */
    fun add(
        context: Context,
        text: String,
        dueAt: Long? = null,
        turnIndex: Int = 0
    ): String {
        val clean = text.trim().take(200)
        if (clean.isEmpty()) return ""
        val id = java.util.UUID.randomUUID().toString().take(16)
        val now = System.currentTimeMillis()
        val list = load(context)
        if (list.size >= MAX_ITEMS) {
            // Drop the oldest done follow-up first, then the oldest overall.
            val drop = list.firstOrNull { it.done } ?: list.firstOrNull()
            if (drop != null) remove(context, drop.id)
        }
        val item = FollowUp(id = id, text = clean, createdAt = now, dueAt = dueAt, done = false, mentionedAtTurn = turnIndex)
        val updated = (list + item).take(MAX_ITEMS)
        save(context, updated)
        return id
    }

    /** Mark a follow-up as done. */
    fun done(context: Context, id: String): Boolean {
        val list = load(context)
        val target = list.firstOrNull { it.id == id } ?: return false
        val updated = list.map { if (it.id == id) it.copy(done = true) else it }
        save(context, updated)
        return true
    }

    /** Remove a follow-up. */
    fun remove(context: Context, id: String): Boolean {
        val list = load(context)
        val filtered = list.filter { it.id != id }
        if (filtered.size == list.size) return false
        save(context, filtered)
        return true
    }

    /** All pending follow-ups, sorted by due date then creation time. */
    fun pending(context: Context): List<FollowUp> =
        load(context).filter { !it.done }
            .sortedWith(
                compareBy<FollowUp> { it.dueAt ?: Long.MAX_VALUE }
                    .thenByDescending { it.createdAt }
            )

    /** Follow-ups due within the lookahead window (minutes). */
    dueSoon(context: Context, withinMinutes: Long, now: Long = System.currentTimeMillis()): List<FollowUp> =
        pending(context).filter { item ->
            item.dueAt != null && item.dueAt!! > now && item.dueAt!! <= now + withinMinutes * 60_000L
        }

    /** Overdue follow-ups (due date passed and not done). */
    fun overdue(context: Context, now: Long = System.currentTimeMillis()): List<FollowUp> =
        pending(context).filter { item ->
            item.dueAt != null && item.dueAt!! <= now
        }

    fun countPending(context: Context): Int = pending(context).size

    private fun load(context: Context): List<FollowUp> {
        val raw = prefs(context).getString(KEY_ITEMS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                FollowUp(
                    id = o.optString("id", ""),
                    text = o.optString("text", ""),
                    createdAt = o.optLong("createdAt", 0L),
                    dueAt = if (o.isNull("dueAt") || !o.has("dueAt")) null else o.optLong("dueAt"),
                    done = o.optBoolean("done", false),
                    mentionedAtTurn = o.optInt("mentionedAtTurn", 0)
                )
            }.filter { it.id.isNotBlank() && it.text.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(context: Context, list: List<FollowUp>) {
        val arr = JSONArray()
        list.forEach { item ->
            arr.put(JSONObject()
                .put("id", item.id)
                .put("text", item.text)
                .put("createdAt", item.createdAt)
                .put("dueAt", item.dueAt ?: JSONObject.NULL)
                .put("done", item.done)
                .put("mentionedAtTurn", item.mentionedAtTurn))
        }
        prefs(context).edit().putString(KEY_ITEMS, arr.toString()).apply()
    }

    companion object {
        @Volatile private var idCounter = AtomicInteger(0)
    }
}
