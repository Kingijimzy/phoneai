package com.phoneai.app

import android.content.Context
import org.json.JSONArray

data class OrionSavedChat(
    val timestamp: Long,
    val title: String,
    val messages: List<Pair<String, String>>,
    val sessionId: String = timestamp.toString()
)

/**
 * Public facade retained for existing UI/service call sites.
 * Storage is backed by OrionConversationRepository (SQLite), with one-time import
 * from the previous SharedPreferences format for existing installs.
 */
object ChatHistoryStore {
    private fun repo(context: Context) = OrionConversationRepository.get(context)

    @Synchronized
    fun search(context: Context, query: String, limit: Int = 8): List<OrionConversationRepository.SearchHit> = repo(context).searchHistory(context, query, limit)

    @Synchronized
    fun recent(context: Context, limit: Int = 8): List<OrionConversationRepository.SearchHit> = repo(context).recentHistory(context, limit)

    @Synchronized
    fun recordObjectiveEvidence(context: Context, objectiveId: String, tool: String, outcome: String, summary: String, turnId: String? = null): OrionConversationRepository.ObjectiveEvidence? = repo(context).recordObjectiveEvidence(context, objectiveId, tool, outcome, summary, turnId)

    @Synchronized
    fun objectiveEvidence(context: Context, objectiveId: String): List<OrionConversationRepository.ObjectiveEvidence> = repo(context).objectiveEvidence(context, objectiveId)

    @Synchronized
    fun load(context: Context): List<Pair<String, String>> = repo(context).loadActive(context)

    @Synchronized
    fun save(context: Context, transcript: List<Pair<String, String>>) = repo(context).saveActive(context, transcript)

    @Synchronized
    fun archiveAndClear(context: Context, transcript: List<Pair<String, String>>) =
        repo(context).archiveAndClear(context, transcript)

    @Synchronized
    fun archived(context: Context): List<OrionSavedChat> =
        repo(context).archived(context).map { OrionSavedChat(it.timestamp, it.title, it.messages, it.id) }

    @Synchronized
    fun renameArchivedById(context: Context, sessionId: String, title: String): Boolean =
        repo(context).renameArchivedById(context, sessionId, title)

    @Synchronized
    fun deleteArchivedById(context: Context, sessionId: String): Boolean = repo(context).deleteArchivedById(context, sessionId)

    @Synchronized
    fun restoreArchivedById(context: Context, sessionId: String): List<Pair<String, String>>? =
        repo(context).restoreArchivedById(context, sessionId)

    @Synchronized
    fun deleteArchived(context: Context, timestamp: Long, title: String): Boolean =
        repo(context).deleteArchived(context, timestamp, title)

    @Synchronized
    fun deleteArchived(context: Context, timestamp: Long): Boolean =
        repo(context).deleteArchived(context, timestamp)

    @Synchronized
    fun clearArchived(context: Context): Boolean = repo(context).clearArchived(context)

    @Synchronized
    fun restoreActive(context: Context, transcript: List<Pair<String, String>>) =
        repo(context).restoreActive(context, transcript)

    @Synchronized
    fun clearAll(context: Context): Boolean = repo(context).clearAll(context)

    @Synchronized
    fun clear(context: Context) = repo(context).clear(context)

    /** Kept for regression tests that exercise legacy JSON migration/filtering. */
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
