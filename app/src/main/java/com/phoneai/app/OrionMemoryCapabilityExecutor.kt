package com.phoneai.app

import org.json.JSONObject

/**
 * Owns persistent user memory, local knowledge retrieval, graph facts and self-review.
 * Agent remains responsible for shared policy, authorization, evidence and cancellation gates.
 */
internal object OrionMemoryCapabilityExecutor {
    private val supportedTools = setOf(
        "remember", "update_memory", "forget", "recall", "knowledge_search",
        "graph_link", "graph_query", "self_review"
    )

    fun supports(tool: String): Boolean = tool in supportedTools

    fun execute(tool: String, args: JSONObject, service: PhoneAiService, tools: Tools): String =
        when (tool) {
            "remember" -> {
                val text = args.optString("text")
                val category = args.optString("category", "fact")
                val before = MemoryStore.all(service).firstOrNull {
                    it.text.equals(text.trim().replace("\n", " "), true)
                }
                val result = tools.remember(category, text)
                if (!result.startsWith("error:", true)) {
                    runCatching { KnowledgeGraph.learnFrom(service, text) }
                    val id = Regex("\\[(\\d+)\\]").find(result)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val after = id?.let { target -> MemoryStore.all(service).firstOrNull { it.id == target } }
                    if (after != null) UndoStore.recordMemory(
                        service, if (before == null) "memory_add" else "memory_update", before, after
                    )
                }
                result
            }
            "update_memory" -> {
                val id = args.optInt("id", -1)
                val before = MemoryStore.all(service).firstOrNull { it.id == id }
                val result = tools.updateMemory(
                    id,
                    if (args.has("category")) args.optString("category") else null,
                    if (args.has("text")) args.optString("text") else null
                )
                val after = MemoryStore.all(service).firstOrNull { it.id == id }
                if (!result.startsWith("error:", true) && before != null && after != null)
                    UndoStore.recordMemory(service, "memory_update", before, after)
                result
            }
            "forget" -> {
                val id = args.optInt("id", -1)
                val before = MemoryStore.all(service).firstOrNull { it.id == id }
                val result = tools.forget(id)
                if (!result.startsWith("error:", true) && before != null)
                    UndoStore.recordMemory(service, "memory_delete", before, null)
                result
            }
            "recall" -> tools.recall(args.optString("query"))
            "knowledge_search" -> tools.knowledgeSearch(args.optString("query"))
            "graph_link" -> tools.graphLink(args.optString("a"), args.optString("relation"), args.optString("b"))
            "graph_query" -> tools.graphQuery(args.optString("entity"))
            "self_review" -> tools.selfReview()
            else -> "error: memory executor does not implement '$tool'."
        }
}
