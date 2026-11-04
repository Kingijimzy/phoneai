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
            (node.status == Status.PENDING || node.status == Status.READY || node.status == Status.BLOCKED || node.status == Status.FAILED) &&
                node.dependsOn.all { dep -> nodes.firstOrNull { it.id == dep }?.status == Status.COMPLETE }
        }
        fun isComplete(): Boolean = nodes.isNotEmpty() && nodes.all { it.status == Status.COMPLETE }
    }
    sealed class ParseResult {
        data class Valid(val graph: Graph) : ParseResult()
        data class Invalid(val reason: String) : ParseResult()
    }
    private const val PREFS = "orion_objective_graph_v1"
    private const val LAST_SESSION_KEY = "last_session"
    private fun graphKey(sessionId: String) = "graph_session_" + sessionId
    private val idPattern = Regex("^[a-z][a-z0-9_-]{0,31}$")

    fun parse(raw: String, objective: String, restoreState: Boolean = false): ParseResult {
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
            val status = if (restoreState) runCatching { Status.valueOf(item.optString("status", "PENDING").uppercase()) }.getOrDefault(Status.PENDING) else Status.PENDING
            val evidence = if (restoreState) item.optString("evidence").take(1000) else ""
            nodes += Objective(id, title, deps, status, evidence)
        }
        val ids = nodes.map { it.id }.toSet()
        nodes.forEach { node ->
            val missing = node.dependsOn.firstOrNull { it !in ids }
            if (missing != null) return ParseResult.Invalid("Objective '${node.id}' depends on missing objective '$missing'.")
        }
        return try { ParseResult.Valid(Graph(objective.trim().take(1000), topologicalOrder(nodes))) }
        catch (_: IllegalArgumentException) { ParseResult.Invalid("Objective dependencies contain a cycle.") }
    }

    /**
     * Build a safe fallback graph from an already-returned ordered plan. IDs and dependencies
     * are generated locally; the model cannot inject completion state or evidence.
     */
    fun fromPlan(objective: String, titles: List<String>, sequential: Boolean): Graph? {
        val clean = titles.map { it.trim().take(240) }
            .filter { it.isNotBlank() }
            .take(24)
        if (objective.isBlank() || clean.size !in 2..24) return null
        val nodes = clean.mapIndexed { index, title ->
            val id = "step_" + (index + 1)
            val dependencies = if (sequential && index > 0) listOf("step_$index") else emptyList()
            Objective(id = id, title = title, dependsOn = dependencies)
        }
        return runCatching { Graph(objective.trim().take(1000), topologicalOrder(nodes)) }.getOrNull()
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

    /** Merge model proposals while preserving runtime status/evidence already recorded locally. */
    fun applyProposal(context: Context, raw: String, objective: String): ParseResult {
        val parsed = parse(raw, objective)
        val proposed = (parsed as? ParseResult.Valid)?.graph ?: return parsed
        val old = load(context)
        val merged = if (old == null) proposed else {
            val oldById = old.nodes.associateBy { it.id }
            val proposedIds = proposed.nodes.map { it.id }.toSet()
            val next = proposed.nodes.map { node ->
                val prior = oldById[node.id]
                if (prior == null) node
                else if (prior.status != Status.PENDING && prior.status != Status.READY) prior
                else node.copy(status = prior.status, evidence = prior.evidence)
            }.toMutableList()
            old.nodes.filter { it.id !in proposedIds && it.status != Status.PENDING && it.status != Status.READY && !(it.id == "main" && old.nodes.size == 1 && it.status == Status.RUNNING) }.forEach { next += it }
            Graph(objective.trim().take(1000), topologicalOrder(next))
        }
        return if (save(context, merged)) ParseResult.Valid(merged) else ParseResult.Invalid("Objective graph could not be persisted.")
    }

    fun canExecute(graph: Graph, id: String): String? {
        val node = graph.nodes.firstOrNull { it.id == id } ?: return "Unknown objective ID: " + id
        if (node.status == Status.COMPLETE) return "Objective is already complete: " + id
        val blocked = node.dependsOn.firstOrNull { dep -> graph.nodes.firstOrNull { it.id == dep }?.status != Status.COMPLETE }
        if (blocked != null) return "Objective " + id + " is blocked until prerequisite " + blocked + " is complete."
        return null
    }

    /**
     * Resolve the planner's objective_id at dispatch time.
     *
     * Models sometimes invent a descriptive ID (for example "calc_write") even when the
     * persisted graph contains one canonical node ("main"). A single-node graph is unambiguous,
     * so safely bind the call to that node. Multi-node graphs must supply an exact known ID;
     * guessing there could execute work against the wrong dependency.
     */
    /**
     * Task Centre bookkeeping is not execution of an objective capability.
     * These tools manage user-visible task records; they must not inherit the active
     * objective's dependency gate or create execution evidence for that objective.
     */
    fun requiresObjectiveDispatch(tool: String): Boolean =
        tool !in setOf("task_add", "task_update", "task_list")

    fun resolveDispatchId(graph: Graph, requestedId: String?, tool: String = ""): String? {
        val candidate = requestedId?.trim()?.lowercase().orEmpty()
        graph.nodes.firstOrNull { it.id == candidate }?.let { return it.id }

        // Repair model/tool naming mismatches by matching the capability to a unique ready
        // objective. This never picks between ambiguous objectives and never bypasses dependencies.
        val hints = when (tool.lowercase()) {
            "open_app", "lookup_app", "list_apps", "open_deep_link", "system_intent",
            "read_screen", "look", "battery", "device_info" -> setOf("phone", "device", "app", "capability")
            "python_exec" -> setOf("python", "diagnostic", "script", "execute")
            "workspace_read" -> setOf("verify", "report", "workspace", "read")
            "workspace_write", "save_file" -> setOf("save", "report", "file", "workspace", "script")
            "phone_battery", "phone.*" -> setOf("phone", "device", "battery", "capability")
            else -> setOf(tool.lowercase().replace(Regex("[^a-z0-9]+"), "_"))
        }
        val ready = graph.ready()
        val semanticMatches = ready.filter { node ->
            val words = (node.id + " " + node.title).lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()
            hints.any { hint -> hint in words || (hint.length > 4 && node.title.contains(hint, ignoreCase = true)) }
        }
        if (semanticMatches.size == 1) return semanticMatches.single().id

        // A running objective is the only safe target for follow-up calls in a multi-tool step.
        return graph.nodes.singleOrNull { it.status == Status.RUNNING }?.id
            ?: ready.singleOrNull()?.id
            ?: graph.nodes.singleOrNull()?.id
    }

    fun startExecution(context: Context, id: String): Boolean {
        val graph = load(context) ?: return false
        if (canExecute(graph, id) != null) return false
        return save(context, updateStatus(graph, id, Status.RUNNING))
    }

    fun recordExecution(context: Context, id: String, tool: String, result: String): String? {
        val outcome = when {
            result.trimStart().startsWith("error", true) -> "failed"
            result.contains("BROWSER HANDOFF REQUIRED", ignoreCase = true) -> "pending_user"
            else -> "success"
        }
        val receipt = ChatHistoryStore.recordObjectiveEvidence(context, id, tool, outcome, result.take(700)) ?: return null
        val graph = load(context) ?: return null
        if (graph.nodes.none { it.id == id }) return null
        val status = if (outcome == "success") Status.RUNNING else Status.BLOCKED
        return if (save(context, updateStatus(graph, id, status, receipt.id))) receipt.id else null
    }

    /** A model can request completion, but only an exact stored successful executor receipt can authorize it. */
    fun completeFromEvidence(context: Context, id: String, evidenceId: String): Boolean {
        val graph = load(context) ?: return false
        if (canExecute(graph, id) != null) return false
        val node = graph.nodes.firstOrNull { it.id == id } ?: return false
        if (node.evidence != evidenceId) return false
        val receipt = ChatHistoryStore.objectiveEvidence(context, id).firstOrNull {
            it.id == evidenceId && it.outcome == "success"
        } ?: return false
        if (receipt.sessionId != ChatHistoryStore.activeSessionId(context)) return false
        return save(context, updateStatus(graph, id, Status.COMPLETE, receipt.id))
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
            if (node.evidence.isNotBlank()) append("; last_executor_evidence_id=${node.evidence.take(80)}")
            appendLine()
        }
        appendLine("Only execute objectives whose dependencies are COMPLETE. Task Centre tools task_add, task_update and task_list manage separate user task records; NEVER call task_update to mark an objective complete or to change this graph. Objective completion is recorded only with complete_objectives using the exact executor evidence_id already attached to that objective. A tool call being attempted is not completion evidence. Preserve blocked and failed nodes; do not silently mark them complete.")
    }

    fun save(context: Context, graph: Graph): Boolean = runCatching {
        val sessionId = ChatHistoryStore.activeSessionId(context) ?: return@runCatching false
        val root = JSONObject().put("objective", graph.objective).put("sessionId", sessionId).put("updatedAt", graph.updatedAt)
        val array = JSONArray()
        graph.ordered().forEach { node ->
            array.put(JSONObject().put("id", node.id).put("title", node.title).put("dependsOn", JSONArray(node.dependsOn))
                .put("status", node.status.name).put("evidence", node.evidence))
        }
        root.put("objectives", array)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(graphKey(sessionId), root.toString())
            .putString(LAST_SESSION_KEY, sessionId)
            .commit()
    }.getOrDefault(false)

    fun load(context: Context): Graph? {
        val sessionId = ChatHistoryStore.activeSessionId(context) ?: return null
        runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(LAST_SESSION_KEY, sessionId).apply() }
        val raw = runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(graphKey(sessionId), null)
        }.getOrNull() ?: return null
        val objective = runCatching { JSONObject(raw).optString("objective") }.getOrDefault("")
        return (parse(raw, objective, restoreState = true) as? ParseResult.Valid)?.graph
    }

    fun clear(context: Context): Boolean = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sessionId = ChatHistoryStore.activeSessionId(context) ?: prefs.getString(LAST_SESSION_KEY, null)
        val editor = prefs.edit().remove(LAST_SESSION_KEY).remove("active_graph")
        if (!sessionId.isNullOrBlank()) editor.remove(graphKey(sessionId))
        editor.commit()
    }.getOrDefault(false)
}
