package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Automatic memory lifecycle (reset Rule 4):
 * Observe → Extract → Evaluate → Deduplicate → Store → Retrieve.
 *
 * Does not require the user to manually insert memory rows for routine facts.
 * Provenance, timestamps, and confidence are always recorded.
 */
internal object OrionMemoryLifecycle {
    private const val PREFS = "orion_memory_lifecycle_v1"
    private const val KEY = "entries"

    data class Entry(
        val id: String,
        val text: String,
        val category: String,
        val confidence: Double,
        val provenance: String,
        val createdAt: Long,
        val updatedAt: Long
    )

    /** Observe a successful tool outcome and store a compact durable fact when useful. */
    fun observeToolSuccess(context: Context, userGoal: String, tool: String, result: String) {
        val goal = userGoal.trim()
        val res = result.trim()
        if (goal.isBlank() || res.isBlank()) return
        if (res.startsWith("error", true)) return

        val candidate = when {
            tool == "play_media" || tool == "open_app" ->
                "User asked to open/play media: ${goal.take(120)}. Outcome: ${res.take(120)}"
            tool.contains("memory", true) -> return // already a memory write
            res.length < 12 -> return
            else -> "Task note (${tool}): goal=${goal.take(80)}; result=${res.take(120)}"
        }
        upsert(
            context,
            text = candidate,
            category = "task_outcome",
            confidence = 0.55,
            provenance = "tool:$tool"
        )
    }

    /** Observe an explicit user preference statement. */
    fun observeUserPreference(context: Context, utterance: String) {
        val t = utterance.trim()
        if (t.length < 8) return
        val looksPref = Regex("(?i)\\b(i (prefer|like|want|use|always|never)|remember that|my name is)\\b").containsMatchIn(t)
        if (!looksPref) return
        upsert(
            context,
            text = t.take(240),
            category = "preference",
            confidence = 0.7,
            provenance = "user_utterance"
        )
    }

    fun retrieveRelevant(context: Context, query: String, limit: Int = 6): List<Entry> {
        val q = query.lowercase()
        val tokens = Regex("[a-z0-9]{3,}").findAll(q).map { it.value }.toSet()
        if (tokens.isEmpty()) return load(context).takeLast(limit)
        return load(context)
            .map { e ->
                val score = tokens.count { t -> e.text.lowercase().contains(t) } +
                    if (e.category == "preference") 1 else 0
                e to score
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second * 10 + it.first.confidence }
            .map { it.first }
            .take(limit)
    }

    fun promptBlock(context: Context, query: String): String {
        val hits = retrieveRelevant(context, query)
        if (hits.isEmpty()) return ""
        return buildString {
            appendLine("RELEVANT AUTOMATIC MEMORY (hints only; re-check live state):")
            hits.forEach { e ->
                appendLine("- [${e.category} c=${"%.2f".format(e.confidence)} via ${e.provenance}] ${e.text}")
            }
        }.trim()
    }

    fun delete(context: Context, id: String): Boolean {
        val next = load(context).filterNot { it.id == id }
        return save(context, next)
    }

    private fun upsert(context: Context, text: String, category: String, confidence: Double, provenance: String) {
        val now = System.currentTimeMillis()
        val existing = load(context).toMutableList()
        val norm = text.lowercase().take(160)
        val dup = existing.indexOfFirst { it.text.lowercase().take(160) == norm }
        if (dup >= 0) {
            val old = existing[dup]
            existing[dup] = old.copy(
                confidence = maxOf(old.confidence, confidence),
                updatedAt = now,
                provenance = old.provenance + "|" + provenance
            )
        } else {
            existing += Entry(
                id = "m-" + now.toString(36),
                text = text.take(400),
                category = category,
                confidence = confidence,
                provenance = provenance,
                createdAt = now,
                updatedAt = now
            )
        }
        // Cap store size
        val trimmed = existing.sortedByDescending { it.updatedAt }.take(200)
        save(context, trimmed)
    }

    private fun load(context: Context): List<Entry> = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Entry(
                id = o.optString("id"),
                text = o.optString("text"),
                category = o.optString("category"),
                confidence = o.optDouble("confidence", 0.5),
                provenance = o.optString("provenance"),
                createdAt = o.optLong("createdAt"),
                updatedAt = o.optLong("updatedAt")
            )
        }
    }.getOrDefault(emptyList())

    private fun save(context: Context, entries: List<Entry>): Boolean = runCatching {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("text", e.text)
                    .put("category", e.category)
                    .put("confidence", e.confidence)
                    .put("provenance", e.provenance)
                    .put("createdAt", e.createdAt)
                    .put("updatedAt", e.updatedAt)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).commit()
    }.getOrDefault(false)
}
