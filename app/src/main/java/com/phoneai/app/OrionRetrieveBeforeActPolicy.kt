package com.phoneai.app

/**
 * Dispatch-boundary proof that relevant persistent context was retrieved for this objective.
 * The receipt is process-local and cannot be supplied or forged through model tool arguments.
 */
internal data class OrionMemoryRetrievalReceipt(
    val objectiveKey: String,
    val retrievedAtMs: Long,
    val retrievedChars: Int,
    val source: String
)

internal object OrionRetrieveBeforeActPolicy {
    private val readOnlyMemoryTools = setOf(
        "recall", "discussion_search", "decision_history", "knowledge_search", "graph_query"
    )

    fun requiresRetrieval(tool: String, access: ToolAccess, consequential: Boolean): Boolean {
        if (tool in readOnlyMemoryTools) return false
        return access == ToolAccess.WRITE || consequential
    }

    fun objectiveKey(request: String): String =
        request.trim().replace(Regex("\\s+"), " ").lowercase().take(1200)

    fun receipt(
        request: String,
        retrievedContext: String,
        now: Long = System.currentTimeMillis()
    ): OrionMemoryRetrievalReceipt? {
        val key = objectiveKey(request)
        if (key.isBlank()) return null
        return OrionMemoryRetrievalReceipt(
            objectiveKey = key,
            retrievedAtMs = now,
            retrievedChars = retrievedContext.length,
            source = "persistent_memory_context"
        )
    }

    fun blockReason(
        request: String,
        receipt: OrionMemoryRetrievalReceipt?,
        now: Long = System.currentTimeMillis(),
        maxAgeMs: Long = 60_000L
    ): String? {
        val key = objectiveKey(request)
        if (key.isBlank()) return "the active objective is unavailable"
        if (receipt == null) return "persistent memory retrieval did not complete"
        if (receipt.objectiveKey != key) return "the retrieved memory belongs to a different objective"
        if (receipt.retrievedAtMs > now || now - receipt.retrievedAtMs > maxAgeMs) {
            return "the retrieved memory context is stale"
        }
        if (receipt.source != "persistent_memory_context" || receipt.retrievedChars < 0) {
            return "the memory retrieval receipt is invalid"
        }
        return null
    }
}
