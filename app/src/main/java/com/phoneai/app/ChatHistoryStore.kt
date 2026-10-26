package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class OrionSavedChat(val timestamp: Long, val title: String, val messages: List<Pair<String, String>>)

/** Private on-device chat storage. Only user/assistant turns are retained, never activity notes. */
object ChatHistoryStore {
    private const val PREFS = "orion_chat_history"
    private const val KEY = "active_transcript"
    private const val ARCHIVE = "archived_chats"
    private const val MAX_TURNS = 80
    private const val MAX_CHATS = 15

    private fun clean(turns: List<Pair<String, String>>): List<Pair<String, String>> =
        turns.filter { (kind, text) -> (kind == "me" || kind == "ai") && text.isNotBlank() }
            .takeLast(MAX_TURNS).map { it.first to it.second.take(6000) }

    @Synchronized
    fun load(context: Context): List<Pair<String, String>> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val result = ArrayList<Pair<String, String>>()
            val start = (arr.length() - MAX_TURNS).coerceAtLeast(0)
            for (i in start until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val kind = item.optString("kind")
                val text = item.optString("text").take(6000)
                if ((kind == "me" || kind == "ai") && text.isNotBlank()) result.add(kind to text)
            }
            result
        } catch (_: Exception) { emptyList() }
    }

    @Synchronized
    fun save(context: Context, transcript: List<Pair<String, String>>) {
        val arr = JSONArray()
        clean(transcript).forEach { (kind, text) -> arr.put(JSONObject().put("kind", kind).put("text", text)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    @Synchronized
    fun archiveAndClear(context: Context, transcript: List<Pair<String, String>>) {
        val turns = clean(transcript)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (turns.any { it.first == "me" }) {
            val archives = try { JSONArray(prefs.getString(ARCHIVE, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
            val messages = JSONArray()
            turns.forEach { (kind, text) -> messages.put(JSONObject().put("kind", kind).put("text", text)) }
            val title = turns.firstOrNull { it.first == "me" }?.second?.replace("\n", " ")?.take(64) ?: "Conversation"
            val session = JSONObject().put("timestamp", System.currentTimeMillis()).put("title", title).put("messages", messages)
            val rebuilt = JSONArray().put(session)
            for (i in 0 until archives.length()) {
                val old = archives.optJSONObject(i) ?: continue
                if (rebuilt.length() < MAX_CHATS) rebuilt.put(old)
            }
            prefs.edit().putString(ARCHIVE, rebuilt.toString()).remove(KEY).apply()
        } else {
            prefs.edit().remove(KEY).apply()
        }
    }

    @Synchronized
    fun archived(context: Context): List<OrionSavedChat> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ARCHIVE, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val a = o.optJSONArray("messages") ?: JSONArray()
                val turns = ArrayList<Pair<String, String>>()
                for (j in 0 until a.length()) {
                    val m = a.optJSONObject(j) ?: continue
                    val kind = m.optString("kind")
                    val text = m.optString("text")
                    if ((kind == "me" || kind == "ai") && text.isNotBlank()) turns.add(kind to text)
                }
                OrionSavedChat(o.optLong("timestamp"), o.optString("title", "Conversation"), turns)
            }
        } catch (_: Exception) { emptyList() }
    }

    /** Delete one archived conversation using its stable timestamp/title identity. */
    @Synchronized
    fun deleteArchived(context: Context, timestamp: Long, title: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(ARCHIVE, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        val kept = JSONArray()
        var removed = false
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            if (!removed && item.optLong("timestamp") == timestamp && item.optString("title") == title) {
                removed = true
            } else {
                kept.put(item)
            }
        }
        val saved = prefs.edit().putString(ARCHIVE, kept.toString()).commit()
        return removed && saved
    }

    /** Delete all archived conversations without affecting the active transcript. */
    @Synchronized
    fun clearArchived(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(ARCHIVE, "[]").commit()

    @Synchronized
    fun restoreActive(context: Context, transcript: List<Pair<String, String>>) {
        save(context, transcript)
    }

    /** Delete one archived conversation by its persisted timestamp; commit before reporting success. */
    @Synchronized
    fun deleteArchived(context: Context, timestamp: Long): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(ARCHIVE, "[]") ?: "[]"
        val updated = removeArchivedEntry(raw, timestamp) ?: return false
        return prefs.edit().putString(ARCHIVE, updated).commit()
    }

    /** Remove active and archived transcripts together. Memory notes are stored separately. */
    @Synchronized
    fun clearAll(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).remove(ARCHIVE).commit()

    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    /** Pure archive filter so timestamp deletion can be regression-tested without Android storage. */
    internal fun removeArchivedEntry(raw: String, timestamp: Long): String? = try {
        val source = JSONArray(raw)
        val kept = JSONArray()
        var removed = false
        for (i in 0 until source.length()) {
            val item = source.optJSONObject(i) ?: continue
            if (!removed && item.optLong("timestamp", Long.MIN_VALUE) == timestamp) {
                removed = true
            } else {
                kept.put(item)
            }
        }
        if (removed) kept.toString() else null
    } catch (_: Exception) {
        null
    }
}
