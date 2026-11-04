package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Validated, durable multi-objective task graph. It never dispatches tools or grants permission. */
internal object OrionObjectiveGraph {
    enum class Status { PENDING, READY, RUNNING, BLOCKED, COMPLETE, FAILED }

    data class Objective(
        val id: String,
        val title: String,
        val dependsOn: List<String> = emptyList(),
        val status: Status = Status.PENDING,
        val evidence: String = ""
    )

    data class Graph(
        val objective: String,
        val nodes: List<Objective>,
        val updatedAt: Long = System.currentTimeMillis()
    ) {
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

    private const val PREFS = "orion_objective_graph"
    private const val LAST_SESSION_KEY = "last_session"
    private fun graphKey(sessionId: String) = "graph_session_" + sessionId
    private val idPattern = Regex("^[a-z][a-z0-9_-]{0,39}$")

    fun parse(raw: String, objective: String, restoreState: Boolean = false): ParseResult {
        val root = try { JSONObject(raw) } catch (_: Exception) { return ParseResult.Invalid("Objective graph is not valid JSON.") }
        val arr = root.optJSONArray("objectives") ?: return ParseResult.Invalid("Objective graph requires an objectives array.")
        if (arr.length() < 1) return ParseResult.Invalid("Objective graph needs at least one objective.")
        if (arr.length() > 24) return ParseResult.Invalid("Objective graph is limited to 24 objectives.")
        val nodes = ArrayList<Objective>()
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: return ParseResult.Invalid("Objective entry $i is not an object.")
            val id = o.optString("id").trim().lowercase()
            if (!idPattern.matches(id)) return ParseResult.Invalid("Objective id must be snake_case [a-z0-9_-].")
            if (!seen.add(id)) return ParseResult.Invalid("Duplicate objective id '$id'.")
            val title = o.optString("title").trim().ifBlank { o.optString("name").trim() }.take(240)
            if (title.isBlank()) return ParseResult.Invalid("Objective '$id' needs a title.")
            val depsArr = o.optJSONArray("dependsOn") ?: o.optJSONArray("depends_on") ?: JSONArray()
            val deps = ArrayList<String>()
            for (j in 0 until depsArr.length()) {
                val d = depsArr.optString(j).trim().lowercase()
                if (d.isBlank()) continue
                if (d == id) return ParseResult.Invalid("Objective '$id' cannot depend on itself.")
                deps += d
            }
            if (deps.size > 24) return ParseResult.Invalid("Objective '$id' has too many dependencies.")
            if (deps.size != deps.distinct().size) return ParseResult.Invalid("Objective '$id' has invalid or duplicate dependencies.")
            val status = if (restoreState) {
                runCatching { Status.valueOf(o.optString("status", "PENDING").uppercase()) }.getOrDefault(Status.PENDING)
            } else Status.PENDING
            val evidence = if (restoreState) o.optString("evidence").take(1000) else ""
            nodes += Objective(id, title, deps, status, evidence)
        }
        for (node in nodes) {
            val missing = node.dependsOn.firstOrNull { dep -> nodes.none { it.id == dep } }
            if (missing != null) return ParseResult.Invalid("Objective '${node.id}' depends on missing objective '$missing'.")
        }
        val ordered = try { topologicalOrder(nodes) }
        catch (_: IllegalArgumentException) { return ParseResult.Invalid("Objective dependencies contain a cycle.") }
        return ParseResult.Valid(Graph(objective.trim().take(1000).ifBlank { "Task" }, ordered))
    }

    fun topologicalOrder(nodes: List<Objective>): List<Objective> {
        val byId = nodes.associateBy { it.id }
        val result = ArrayList<Objective>()
        val visiting = HashSet<String>()
        val visited = HashSet<String>()
        fun visit(id: String) {
            if (id in visited) return
            if (id in visiting) throw IllegalArgumentException("cycle")
            visiting += id
            byId[id]?.dependsOn?.forEach { visit(it) }
            visiting -= id
            visited += id
            byId[id]?.let { result += it }
        }
        nodes.forEach { visit(it.id) }
        return result
    }

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

    fun requiresObjectiveDispatch(tool: String): Boolean = tool !in setOf("task_add")

    /**
     * Resolve the planner's objective_id at dispatch time.
     * Models invent descriptive IDs; bind safely to a unique ready/running node without guessing parallel work.
     */
    fun resolveDispatchId(graph: Graph, requestedId: String?, tool: String = ""): String? {
        val candidate = requestedId?.trim()?.lowercase().orEmpty()
        graph.nodes.firstOrNull { it.id == candidate }?.let { return it.id }

        val hints = when (tool.lowercase()) {
            "open_app", "lookup_app", "list_apps", "open_deep_link", "system_intent",
            "read_screen", "look", "battery", "device_info" -> setOf("phone", "device", "app", "capability")
            "python_exec" -> setOf("python", "diagnostic", "script", "execute")
            "workspace_read" -> setOf("verify", "report", "workspace", "read")
            "workspace_write", "save_file" -> setOf("save", "report", "file", "workspace", "script")
            else -> setOf(tool.lowercase().replace(Regex("[^a-z0-9]+"), "_"))
        }
        val ready = graph.ready()
        val semanticMatches = ready.filter { node ->
            val words = (node.id + " " + node.title).lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()
            hints.any { hint -> hint in words || (hint.length > 4 && node.title.contains(hint, ignoreCase = true)) }
        }
        if (semanticMatches.size == 1) return semanticMatches.single().id

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
        val graph = load(context) ?: return null
        val outcome = if (result.startsWith("error:", ignoreCase = true) || result.startsWith("blocked", ignoreCase = true)) "failure" else "success"
        val summary = result.take(400)
        val receipt = ChatHistoryStore.recordObjectiveEvidence(context, id, tool, outcome, summary) ?: return null
        val status = if (outcome == "success") Status.RUNNING else Status.FAILED
        return if (save(context, updateStatus(graph, id, status, receipt.id))) receipt.id else null
    }

    fun completeFromEvidence(context: Context, id: String, evidenceId: String): Boolean {
        val graph = load(context) ?: return false
        if (canExecute(graph, id) != null) return false
        val node = graph.nodes.firstOrNull { it.id == id } ?: return false
        if (node.evidence != evidenceId) return false
        val receipt = ChatHistoryStore.objectiveEvidence(context, id).firstOrNull {
            it.id == evidenceId && it.outcome == "success"
        } ?: return false
        val active = ChatHistoryStore.activeSessionId(context)
        if (active != null && receipt.sessionId != active) return false
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
        appendLine("Only work on objectives whose dependencies are COMPLETE. Update status only from execution evidence. A tool call being attempted is not completion evidence. Preserve blocked and failed nodes; do not silently mark them complete.")
    }

    fun resolveSessionId(context: Context): String {
        ChatHistoryStore.activeSessionId(context)?.takeIf { it.isNotBlank() }?.let { return it }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(LAST_SESSION_KEY, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val minted = "obj-" + System.currentTimeMillis().toString(36)
        prefs.edit().putString(LAST_SESSION_KEY, minted).commit()
        return minted
    }

    fun save(context: Context, graph: Graph): Boolean = runCatching {
        val sessionId = resolveSessionId(context)
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
        val sessionId = resolveSessionId(context)
        runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(LAST_SESSION_KEY, sessionId).apply() }
        val raw = runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(graphKey(sessionId), null)
        }.getOrNull() ?: return null
        val objective = runCatching { JSONObject(raw).optString("objective") }.getOrDefault("")
        return (parse(raw, objective, restoreState = true) as? ParseResult.Valid)?.graph
    }

    fun synthesizeFromCommand(command: String, sequential: Boolean = true): Graph {
        val objective = command.trim().take(1000).ifBlank { "Autonomous task" }
        val titles = ArrayList<String>()
        if (Regex("(?i)python|pytest|unittest").containsMatchIn(command)) {
            titles += "Create or repair Python project in shared workspace"
            titles += "Run Python tests and capture evidence"
        }
        if (Regex("(?i)bug|diagnose|fix|repair").containsMatchIn(command)) {
            titles += "Diagnose failure and apply a verified fix"
        }
        if (Regex("(?i)node|javascript|npm").containsMatchIn(command)) {
            titles += "Create Node.js project in shared workspace"
            titles += "Run Node tests and capture evidence"
        }
        if (Regex("(?i)workspace|terminal|shell").containsMatchIn(command)) {
            titles += "Preserve workspace files and show commands/outputs"
        }
        if (titles.size < 2) {
            titles.clear()
            titles += "Plan and prepare workspace"
            titles += "Execute primary work with tools"
            titles += "Verify results with evidence"
        }
        val uniq = titles.distinct().take(6)
        val nodes = uniq.mapIndexed { index, title ->
            val id = "obj_" + (index + 1)
            val deps = if (!sequential || index == 0) emptyList() else listOf("obj_" + index)
            Objective(id = id, title = title.take(200), dependsOn = deps, status = if (index == 0) Status.READY else Status.PENDING)
        }
        return Graph(objective, topologicalOrder(nodes))
    }

    fun ensureStructuredGraph(context: Context, command: String, sequential: Boolean = true): Graph? {
        val existing = load(context)
        if (existing != null && existing.nodes.size >= 2) return existing
        val graph = synthesizeFromCommand(command, sequential)
        return if (save(context, graph)) graph else null
    }

    fun ensureSingleMain(context: Context, command: String): Graph? {
        val existing = load(context)
        if (existing != null && existing.nodes.isNotEmpty()) return existing
        val graph = Graph(
            command.trim().take(1000).ifBlank { "Task" },
            listOf(Objective(id = "main", title = "Execute requested outcome", dependsOn = emptyList(), status = Status.RUNNING))
        )
        return if (save(context, graph)) graph else null
    }

    fun clear(context: Context): Boolean = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sessionId = ChatHistoryStore.activeSessionId(context) ?: prefs.getString(LAST_SESSION_KEY, null)
        val editor = prefs.edit().remove(LAST_SESSION_KEY).remove("active_graph")
        if (!sessionId.isNullOrBlank()) editor.remove(graphKey(sessionId))
        editor.commit()
    }.getOrDefault(false)
}
