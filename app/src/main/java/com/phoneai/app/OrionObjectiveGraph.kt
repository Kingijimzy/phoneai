package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Validated, durable multi-objective task graph. It never dispatches tools or grants permission. */
internal object OrionObjectiveGraph {
    enum class Status { PENDING, READY, RUNNING, BLOCKED, COMPLETE, FAILED }
    data class Objective(val id: String, val title: String, val dependsOn: List<String> = emptyList(), val status: Status = Status.PENDING, val evidence: String = "")
    data class Graph(val objective: String, val nodes: List<Objective>, val updatedAt: Long = System.currentTimeMillis()) {
        fun ordered(): List<Objective> = topologicalOrder(nodes)
        fun ready(): List<Objective> = ordered().filter { node ->
            (node.status == Status.PENDING || node.status == Status.READY) &&
                node.dependsOn.all { dep -> nodes.firstOrNull { it.id == dep }?.status == Status.COMPLETE }
        }
        fun isComplete(): Boolean = nodes.isNotEmpty() && nodes.all { it.status == Status.COMPLETE }
    }
    sealed class ParseResult {
        data class Valid(val graph: Graph) : ParseResult()
        data class Invalid(val reason: String) : ParseResult()
    }
    private const val PREFS = "orion_objective_graph_v1"
    private const val GRAPH_KEY = "active_graph"
    private val idPattern = Regex("^[a-z][a-z0-9_-]{0,31}$")

    fun parse(raw: String, objective: String): ParseResult {
        val root = try { JSONObject(raw) } catch (_: Exception) { return ParseResult.Invalid("Objective graph must be valid JSON.") }
        val array = root.optJSONArray("objectives") ?: return ParseResult.Invalid("Objective graph is missing the objectives array.")
        if (array.length() !in 1..24) return ParseResult.Invalid("A task graph must contain between 1 and 24 objectives.")
        val nodes = mutableListOf<Objective>()
        val seen = mutableSetOf<String>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: return ParseResult.Invalid("Objective ${i + 1} is not an object.")
            val id = item.optString("id").trim().lowercase()
            val title = item.optString("title").trim()
            if (!idPattern.matches(id)) return ParseResult.Invalid("Objective ${i + 1} has an invalid id.")
            if (!seen.add(id)) return ParseResult.Invalid("Duplicate objective id '$id'.")
            if (title.isBlank() || title.length > 240) return ParseResult.Invalid("Objective '$id' has an invalid title.")
            val depsJson = item.optJSONArray("dependsOn") ?: JSONArray()
            if (depsJson.length() > 24) return ParseResult.Invalid("Objective '$id' has too many dependencies.")
            val deps = (0 until depsJson.length()).map { depsJson.optString(it).trim().lowercase() }
            if (deps.any { !idPattern.matches(it) } || deps.distinct().size != deps.size || id in deps)
                return ParseResult.Invalid("Objective '$id' has invalid or duplicate dependencies.")
            val status = runCatching { Status.valueOf(item.optString("status", "PENDING").uppercase()) }.getOrDefault(Status.PENDING)
            nodes += Objective(id, title, deps, status, item.optString("evidence").take(1000))
        }
        val ids = nodes.map { it.id }.toSet()
        nodes.forEach { node ->
            val missing = node.dependsOn.firstOrNull { it !in ids }
            if (missing != null) return ParseResult.Invalid("Objective '${node.id}' depends on missing objective '$missing'.")
        }
        return try { ParseResult.Valid(Graph(objective.trim().take(1000), topologicalOrder(nodes))) }
        catch (_: IllegalArgumentException) { ParseResult.Invalid("Objective dependencies contain a cycle.") }
    }

    fun topologicalOrder(nodes: List<Objective>): List<Objective> {
        val byId = nodes.associateBy { it.id }
        require(byId.size == nodes.size) { "Duplicate objective IDs." }
        val result = mutableListOf<Objective>()
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(id: String) {
            if (id in visited) return
            require(id !in visiting) { "Dependency cycle." }
            val node = byId[id] ?: throw IllegalArgumentException("Missing dependency.")
            visiting += id
            node.dependsOn.forEach(::visit)
            visiting -= id
            visited += id
            result += node
        }
        nodes.forEach { visit(it.id) }
        return result
    }

    fun updateStatus(graph: Graph, id: String, status: Status, evidence: String = graph.nodes.firstOrNull { it.id == id }?.evidence.orEmpty()): Graph {
        require(graph.nodes.any { it.id == id }) { "Unknown objective '$id'." }
        val updated = graph.nodes.map { if (it.id == id) it.copy(status = status, evidence = evidence.take(1000)) else it }
        require(updated.filter { it.status == Status.COMPLETE }.all { node ->
            node.dependsOn.all { dep -> updated.firstOrNull { it.id == dep }?.status == Status.COMPLETE }
        }) { "An objective cannot be complete before its dependencies." }
        return graph.copy(nodes = topologicalOrder(updated), updatedAt = System.currentTimeMillis())
    }

    fun prompt(graph: Graph): String = buildString {
        appendLine("DURABLE OBJECTIVE GRAPH:")
        appendLine("Overall objective: ${graph.objective}")
        graph.ordered().forEach { node ->
            append("- ${node.id}: ${node.title}; status=${node.status}; depends_on=")
            append(node.dependsOn.ifEmpty { listOf("none") }.joinToString(","))
            if (node.evidence.isNotBlank()) append("; evidence=${node.evidence.take(240)}")
            appendLine()
        }
        appendLine("Only work on objectives whose dependencies are COMPLETE. Update status only from execution evidence. A tool call being attempted is not completion evidence. Preserve blocked and failed nodes; do not silently mark them complete.")
    }

    fun save(context: Context, graph: Graph): Boolean = runCatching {
        val root = JSONObject().put("objective", graph.objective).put("updatedAt", graph.updatedAt)
        val array = JSONArray()
        graph.ordered().forEach { node ->
            array.put(JSONObject().put("id", node.id).put("title", node.title).put("dependsOn", JSONArray(node.dependsOn))
                .put("status", node.status.name).put("evidence", node.evidence))
        }
        root.put("objectives", array)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(GRAPH_KEY, root.toString()).commit()
    }.getOrDefault(false)

    fun load(context: Context): Graph? {
        val raw = runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(GRAPH_KEY, null) }.getOrNull() ?: return null
        val objective = runCatching { JSONObject(raw).optString("objective") }.getOrDefault("")
        return (parse(raw, objective) as? ParseResult.Valid)?.graph
    }
    fun clear(context: Context): Boolean = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(GRAPH_KEY).commit()
    }.getOrDefault(false)
}
