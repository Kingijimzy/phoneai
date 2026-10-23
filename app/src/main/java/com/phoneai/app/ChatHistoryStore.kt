package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Small on-device transcript store. Only visible user/assistant turns are retained; activity notes are excluded. */
object ChatHistoryStore {
    private const val PREFS = "orion_chat_history"
    private const val KEY = "active_transcript"
    private const val MAX_TURNS = 80

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
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun save(context: Context, transcript: List<Pair<String, String>>) {
        val arr = JSONArray()
        transcript.asSequence()
            .filter { (kind, text) -> (kind == "me" || kind == "ai") && text.isNotBlank() }
            .takeLast(MAX_TURNS)
            .forEach { (kind, text) ->
                arr.put(JSONObject().put("kind", kind).put("text", text.take(6000)))
            }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
