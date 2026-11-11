package com.phoneai.app

import android.content.Context

/**
 * Periodic Cerebro maintenance. The watcher repairs the derived graph index from governed
 * source memories; it never edits source memories, approves proposals, or calls a model.
 */
internal object OrionKnowledgeGraphWatcher {
    const val COOLDOWN_MS = 6L * 60L * 60L * 1000L
    private const val PREFS = "orion_knowledge_graph_watcher_v1"
    private const val LAST_SUCCESS = "last_success_at"

    data class Report(
        val indexedMemories: Int,
        val skippedMemories: Int,
        val failures: Int,
        val summary: String
    )

    internal fun shouldRun(now: Long, lastSuccess: Long): Boolean =
        lastSuccess <= 0L || now - lastSuccess >= COOLDOWN_MS

    fun run(context: Context, force: Boolean = false): Report {
        val c = context.applicationContext
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(LAST_SUCCESS, 0L)
        if (!force && !shouldRun(now, last)) {
            return Report(0, 0, 0, "Knowledge graph is within its six-hour reconciliation window.")
        }

        val memories = MemoryStore.all(c)
        var indexed = 0
        var skipped = 0
        var failures = 0
        memories.forEach { memory ->
            if (!MemoryGovernancePolicy.canUseInPrompt(memory.reviewStatus, memory.sensitivity) ||
                !OrionMemoryLifecycle.isActive(c, memory.id)) {
                skipped++
            } else {
                val result = runCatching { OrionKnowledgeGraph.indexMemory(c, memory) }
                if (result.isSuccess) indexed++ else failures++
            }
        }

        val summary = "Cerebro graph reconciliation: indexed_or_refreshed=" + indexed +
            "; skipped_unapproved_or_inactive=" + skipped + "; failures=" + failures + "."
        // Failed passes remain eligible for retry; never advance the success cursor on partial failure.
        if (failures == 0) prefs.edit().putLong(LAST_SUCCESS, now).commit()
        return Report(indexed, skipped, failures, summary)
    }

    fun clearForTest(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }
}
