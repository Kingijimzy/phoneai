package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable, bounded record of what Orion actually observed, decided, attempted and verified.
 *
 * This is an evidence ledger, not a model-written autobiography: callers must state the event
 * type and outcome explicitly. It deliberately distinguishes completed-but-unverified work.
 */
internal object OrionBehaviourLedger {
    data class Event(
        val id: String,
        val at: Long,
        val category: String,
        val surface: String,
        val summary: String,
        val outcome: String,
        val evidence: String
    )

    private const val PREFS = "orion_behaviour_ledger_v1"
    private const val KEY = "events"
    private const val MAX_EVENTS = 120
    private val lock = Any()

    fun record(
        context: Context,
        category: String,
        surface: String,
        summary: String,
        outcome: String,
        evidence: String = "",
        now: Long = System.currentTimeMillis()
    ): Event? {
        val cleanSummary = AgentPrivacyFilter.safeOutput(summary).replace(Regex("\\s+"), " ").trim().take(500)
        val cleanEvidence = AgentPrivacyFilter.safeOutput(evidence).replace(Regex("\\s+"), " ").trim().take(700)
        if (cleanSummary.isBlank()) return null
        return runCatching {
            synchronized(lock) {
                val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val raw = prefs.getString(KEY, "[]") ?: "[]"
                val old = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
                val event = Event(
                    id = "be_" + now.toString(36) + "_" + (old.length() + 1).toString(36),
                    at = now,
                    category = category.take(48),
                    surface = surface.take(48),
                    summary = cleanSummary,
                    outcome = outcome.take(48),
                    evidence = cleanEvidence
                )
                val next = JSONArray()
                for (i in maxOf(0, old.length() - MAX_EVENTS + 1) until old.length()) {
                    old.optJSONObject(i)?.let { next.put(it) }
                }
                next.put(JSONObject()
                    .put("id", event.id)
                    .put("at", event.at)
                    .put("category", event.category)
                    .put("surface", event.surface)
                    .put("summary", event.summary)
                    .put("outcome", event.outcome)
                    .put("evidence", event.evidence))
                prefs.edit().putString(KEY, next.toString()).apply()
                event
            }
        }.getOrNull()
    }

    fun recent(context: Context, limit: Int = 20): List<Event> = synchronized(lock) {
        runCatching {
            val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, "[]") ?: "[]"
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o ->
                    Event(
                        id = o.optString("id"),
                        at = o.optLong("at"),
                        category = o.optString("category"),
                        surface = o.optString("surface"),
                        summary = o.optString("summary"),
                        outcome = o.optString("outcome"),
                        evidence = o.optString("evidence")
                    )
                }
            }.takeLast(limit.coerceIn(1, MAX_EVENTS))
        }.getOrDefault(emptyList())
    }

    fun summary(context: Context, limit: Int = 8): String {
        val events = recent(context, limit)
        if (events.isEmpty()) return "BEHAVIOUR LEDGER: no recorded events yet."
        return buildString {
            append("BEHAVIOUR LEDGER (recorded outcomes; unverified is not success):")
            events.forEach { event ->
                append("\\n- ").append(event.category).append("/")
                    .append(event.surface).append(" [").append(event.outcome).append("]: ")
                    .append(event.summary)
                if (event.evidence.isNotBlank()) append(" Evidence: ").append(event.evidence)
            }
        }
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
