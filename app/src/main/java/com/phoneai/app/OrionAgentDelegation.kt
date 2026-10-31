package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Least-privilege manifest and durable receipt for specialist work delegated by the central Agent.
 * Current specialist execution is analysis/read-only: it cannot dispatch phone, messaging, shell,
 * publication, account mutation or trading-order tools. The parent task remains the authority.
 */
internal object OrionAgentDelegation {
    enum class Access { ANALYSIS_ONLY, READ_ONLY_RESEARCH, TRADING_ANALYSIS, TASK_PLANNING }

    data class Manifest(
        val id: String,
        val parentTaskId: String?,
        val agentId: String,
        val objective: String,
        val access: Access,
        val allowedTools: Set<String>,
        val memoryScope: String,
        val maxModelCalls: Int = 1,
        val maxCapabilityCalls: Int = 1
    ) {
        fun promptBlock(): String = buildString {
            append("SPECIALIST DELEGATION ENVELOPE:")
            append("\nDelegation ID: ").append(id)
            append("\nParent objective ID: ").append(parentTaskId ?: "not attached")
            append("\nYour assigned objective: ").append(objective)
            append("\nAccess scope: ").append(access.name)
            append("\nAllowed data capabilities: ").append(allowedTools.sorted().joinToString().ifBlank { "none" })
            append("\nMemory scope: ").append(memoryScope)
            append("\nModel-call budget: ").append(maxModelCalls)
            append("\nYou are a specialist worker, not the owner of Orion. Return analysis, sources, drafts or recommendations to the parent. Do not claim to execute phone actions, send messages, publish content, change accounts, run shell commands or place trades. The parent Agent must review your result and dispatch any approved action through its shared policy and task lifecycle.")
        }
    }

    /**
     * Specialists never dispatch directly. The parent supplies its existing guarded executor;
     * this broker narrows the manifest scope and enforces a per-run capability-call budget.
     */
    internal class CapabilityBroker(
        private val manifest: Manifest,
        private val dispatch: (String, JSONObject) -> String
    ) {
        private var calls = 0

        @Synchronized
        fun execute(tool: String, args: JSONObject): String {
            if (tool !in manifest.allowedTools) {
                return "error: delegated capability '$tool' is outside this worker's manifest; nothing was dispatched"
            }
            val registered = ToolRegistry.all.firstOrNull { it.name == tool }
                ?: return "error: delegated capability '$tool' is not registered; nothing was dispatched"
            if (registered.access != ToolAccess.READ) {
                return "error: delegated capability '$tool' is not read-only; nothing was dispatched"
            }
            if (calls >= manifest.maxCapabilityCalls.coerceIn(0, 4)) {
                return "error: specialist capability-call budget exhausted; nothing was dispatched"
            }
            if (args.toString().length > 1200) {
                return "error: delegated capability arguments exceed the bounded input size; nothing was dispatched"
            }
            calls++
            return try {
                dispatch(tool, JSONObject(args.toString()))
            } catch (e: Exception) {
                "error: guarded parent dispatch failed (" + e.javaClass.simpleName + "); outcome requires inspection"
            }
        }

        internal fun callsUsed(): Int = calls
    }

    private val researchTools = setOf("live_web_search", "live_news", "bybit_public_ticker")
    private val coachTools = setOf("bybit_public_ticker")
    private const val PREFS = "orion_agent_delegation_ledger"
    private const val KEY = "runs"
    private const val MAX_RUNS = 40

    fun create(agent: AgentProfile, parentTaskId: String?, objective: String): Manifest {
        val id = UUID.randomUUID().toString()
        val access = when (agent.id) {
            "research" -> Access.READ_ONLY_RESEARCH
            "coach" -> Access.TRADING_ANALYSIS
            "planner" -> Access.TASK_PLANNING
            else -> Access.ANALYSIS_ONLY
        }
        val tools = when (access) {
            Access.READ_ONLY_RESEARCH -> researchTools
            Access.TRADING_ANALYSIS -> coachTools
            Access.ANALYSIS_ONLY, Access.TASK_PLANNING -> emptySet()
        }
        val safeObjective = AgentPrivacyFilter.safeCommand(objective)
            .replace(Regex("(?i)(api[_ -]?key|token|secret|password)\\s*[:=]\\s*[^\\s,;]+"), "[redacted]")
            .replace(Regex("\\s+"), " ").trim().take(700)
        return Manifest(
            id = id,
            parentTaskId = parentTaskId?.takeIf { it.isNotBlank() },
            agentId = agent.id,
            objective = safeObjective,
            access = access,
            allowedTools = tools,
            memoryScope = when (agent.id) {
                "coach" -> "trading preferences, lessons and journal context; read-only"
                "research" -> "relevant saved facts and knowledge passages; read-only"
                "planner" -> "active task summaries; read-only"
                else -> "agent-specific lessons and relevant saved facts; read-only"
            },
            maxModelCalls = if (agent.id == "coach") 2 else 1
        )
    }

    fun begin(context: Context, manifest: Manifest): Boolean = synchronized(this) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val runs = try { JSONArray(prefs.getString(KEY, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        val record = JSONObject()
            .put("id", manifest.id)
            .put("parentTaskId", manifest.parentTaskId)
            .put("agentId", manifest.agentId)
            .put("objective", manifest.objective)
            .put("access", manifest.access.name)
            .put("allowedTools", JSONArray(manifest.allowedTools.sorted()))
            .put("memoryScope", manifest.memoryScope)
            .put("maxModelCalls", manifest.maxModelCalls.coerceIn(1, 3))
            .put("maxCapabilityCalls", manifest.maxCapabilityCalls.coerceIn(0, 4))
            .put("status", "running")
            .put("startedAt", System.currentTimeMillis())
        runs.put(record)
        while (runs.length() > MAX_RUNS) runs.remove(0)
        prefs.edit().putString(KEY, runs.toString()).commit()
    }

    fun finish(context: Context, manifest: Manifest, result: String, failed: Boolean = false): Boolean = synchronized(this) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val runs = try { JSONArray(prefs.getString(KEY, "[]") ?: "[]") } catch (_: Exception) { return@synchronized false }
        val record = (0 until runs.length()).mapNotNull { runs.optJSONObject(it) }
            .firstOrNull { it.optString("id") == manifest.id } ?: return@synchronized false
        record.put("status", if (failed) "failed" else "completed")
            .put("finishedAt", System.currentTimeMillis())
            .put("summary", AgentPrivacyFilter.safeCommand(result).replace(Regex("\\s+"), " ").take(500))
        prefs.edit().putString(KEY, runs.toString()).commit()
    }

    fun latest(context: Context, limit: Int = 10): List<JSONObject> = synchronized(this) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val runs = try { JSONArray(prefs.getString(KEY, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        (maxOf(0, runs.length() - limit.coerceIn(1, MAX_RUNS)) until runs.length())
            .mapNotNull { runs.optJSONObject(it) }
    }

    internal fun clearForTest(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }
}
