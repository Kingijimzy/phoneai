package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Runtime capability registry. Generic owner modes and pack toggles are retired.
 * Actual Android OS permissions, integration authentication, Trading Desk controls and
 * destructive-action confirmation remain enforced by their owning components.
 */
object AgentPolicy {
    enum class Mode(val label: String) { PLAN("Partner operation"), ALLOW("Partner operation") }
    enum class Decision { ALLOW, ASK, PLAN_ONLY, DENY }
    data class Pack(val id: String, val title: String, val description: String, val implemented: Boolean = false)
    data class RuleResult(val decision: Decision, val reason: String)
    val packs = listOf(
        Pack("workspace_shell", "Workspace shell", "Workspace and local runtime capabilities", true),
        Pack("phone_control", "Phone control", "Android actions subject to granted OS permissions", true),
        Pack("termux_bridge", "Termux bridge", "Optional Termux integration; requires its Android-side setup", true),
        Pack("remote_computer", "Remote computer", "Remote computer integration", true),
        Pack("connected_services", "Connected services", "Connected providers and services", true),
        Pack("messaging", "Messaging", "Messaging integrations and Android permissions", true),
        Pack("trading_research", "Trading and coach", "Trading and coach capabilities with their own domain controls", true),
        Pack("gaming_hobby", "Gaming & hobbies", "Gaming and hobby capabilities", true),
        Pack("proactive", "Proactive", "Background and proactive runtime", true),
        Pack("self_test", "Self-test", "Runtime diagnostics", true),
        Pack("orion_core", "Orion core", "Memory, knowledge, tasks and coordination", true),
        Pack("self_evolution", "Self-evolution", "Verified self-extension lifecycle", true)
    )
    private fun p(c: Context) = c.getSharedPreferences("phoneai_agent_policy", Context.MODE_PRIVATE)
    fun mode(c: Context): Mode = Mode.ALLOW
    fun setMode(c: Context, v: Mode) = Unit
    internal fun packEnabled(id: String, savedValue: Boolean?): Boolean =
        packs.any { it.id == id && it.implemented }
    internal val defaultOnPacks = packs.filter { it.implemented }.map { it.id }.toSet()
    internal fun defaultOn(id: String) = packEnabled(id, null)
    fun enabled(c: Context, id: String): Boolean = packEnabled(id, null)
    fun setEnabled(c: Context, id: String, on: Boolean) {
        if (id == "orion_core" || id == "proactive") OrionProactiveWork.refresh(c)
    }
    fun trustedUntil(c: Context) = 0L
    fun startTrustedSession(c: Context, minutes: Int, ids: Set<String>) = Unit
    fun stopTrustedSession(c: Context) = Unit
    fun isTrustedFor(c: Context, id: String) = true
    fun maxSteps(c: Context) = p(c).getInt("budget_steps_v5", 100_000).coerceIn(1, 1_000_000)
    fun maxCalls(c: Context) = p(c).getInt("budget_calls_v5", 100_000).coerceIn(1, 1_000_000)
    fun maxMinutes(c: Context) = p(c).getInt("budget_minutes_v5", 10_080).coerceIn(1, 100_000)
    fun setBudgets(c: Context, steps: Int, calls: Int, minutes: Int) {
        p(c).edit().putInt("budget_steps_v5", steps.coerceIn(1, 1_000_000)).putInt("budget_calls_v5", calls.coerceIn(1, 1_000_000)).putInt("budget_minutes_v5", minutes.coerceIn(1, 100_000)).apply()
    }
    fun packOffMessage(pack: String): String =
        "The capability '$pack' is not implemented or its real platform prerequisite is unavailable."
    internal fun gate(
        c: Context, pack: String, toolId: String, dangerous: Boolean,
        toolMode: ToolMode = ToolMode.ALLOW, askedThisTask: Boolean = true, approvedThisAction: Boolean = false,
        hardBlockReason: String? = null
    ): GatePolicy.Result {
        val verdict = if (!hardBlockReason.isNullOrBlank()) GatePolicy.Verdict.DENY
            else if (pack == "trading_research" && toolId.contains("order", true)) GatePolicy.Verdict.DENY
            else GatePolicy.Verdict.ALLOW
        val reason = when (verdict) {
            GatePolicy.Verdict.DENY -> hardBlockReason ?: "Trading and coach controls do not allow this order action."
            else -> "Partner operation: generic app-level permission prompts are retired."
        }
        return GatePolicy.Result(verdict, reason)
    }

    /** Existing callers keep their RuleResult shape; the decision itself comes from GatePolicy. */
    fun evaluate(c: Context, pack: String, toolId: String, dangerous: Boolean, approvedThisAction: Boolean = false): RuleResult {
        val r = gate(c, pack, toolId, dangerous, toolMode = ToolPermissions.mode(c, toolId), askedThisTask = true, approvedThisAction = approvedThisAction)
        val decision = when (r.verdict) {
            GatePolicy.Verdict.ALLOW -> Decision.ALLOW
            GatePolicy.Verdict.ASK -> Decision.ASK
            GatePolicy.Verdict.DENY -> Decision.DENY
            GatePolicy.Verdict.PLAN_ONLY -> Decision.PLAN_ONLY
        }
        return RuleResult(decision, r.reason)
    }

    /** Maps every built-in tool to exactly one enabled capability pack. Unknown tools fail closed. */
    fun packForTool(tool: String): String? = when (tool) {
        "extension_list", "extension_create", "extension_update", "extension_test", "extension_enable", "extension_run", "extension_disable", "extension_history", "extension_rollback" -> "orion_core"
        "play" -> "gaming_hobby"
        "termux_command" -> "termux_bridge"
        "shell", "workspace_list", "workspace_write", "workspace_read", "workspace_delete", "workspace_move", "python_exec", "project_create", "project_list", "project_inspect", "project_plan", "project_verify" -> "workspace_shell"
        "self_diagnose", "repair_apply", "graph_link", "graph_query", "create_agent", "self_review", "morning_brief", "connect_service", "service_status", "capability_note", "standing_order", "vault_list", "vault_request", "vault_get",
        "goal_add", "goal_list", "goal_remove", "goal_toggle" -> "orion_core"
        "model_decision_log", "model_decision_search", "model_status", "model_offline_mode", "soul_state",
        "proactive_check", "proactive_status", "followup_add", "followup_list", "followup_done", "quiet_period_set" -> "orion_core"
        "chronos_awareness", "calendar_events", "voice_speak", "voice_listen" -> "phone_control"
        "document_extract", "image_read", "image_generate", "backup_list", "backup_restore", "trash_list", "trash_restore", "trash_empty",
        "dev_understand", "dev_read", "dev_write", "dev_python", "dev_shell", "dev_plan", "dev_summary",
        "macro_save", "macro_run", "macro_list", "macro_delete" -> "workspace_shell"
        "github_account_read", "gmail_list_messages" -> "connected_services"
        "trade_history_summary", "trading_calendar", "trading_sessions", "trade_risk_check", "setup_scan",
        "positions_monitor", "bybit_sync", "habit_report", "crypto_derivatives",
        "memecoin_check", "memecoin_watch", "wallet_check", "wallet_watch", "chain_overview",
        "strategy_backtest", "market_forecast", "market_conditions", "strategy_list", "strategy_save",
        "strategy_record_result", "paper_trade", "trading_kill_switch" -> "trading_research"
        "gaming_session_summary", "gaming_session_note_add", "gaming_focus_preset" -> "gaming_hobby"
        "weather", "live_news", "live_web_search", "live_sports", "web_search", "github_public", "github_search_repositories", "fetch_url", "research_web", "bybit_public_ticker" -> "trading_research"
        "telegram_status", "telegram_inbox", "telegram_send", "sms", "notification_action", "resolve_contact" -> "messaging"
        "device_location", "lookup_app", "open_app", "system_intent", "open_deep_link", "read_notifications", "media_status", "media_control", "play_media", "chrome_search",
        "find_files", "open_file", "save_file", "list_apps", "list_app_deeplinks", "read_screen", "look", "validate_action", "verify_action",
        "verify_send", "click", "click_text", "wait_for", "tap", "long_press", "swipe", "swipe_path", "scroll", "type",
        "key", "open_url", "browser_snapshot", "browser_find", "browser_extract", "browser_verify", "browser_click",
        "browser_click_verify", "browser_type", "browser_scroll", "browser_navigate", "browser_back", "browser_tabs",
        "browser_upload", "browser_handoff", "browser_wait", "browser_close", "settings", "dial", "alarm", "timer",
        "volume", "flashlight", "copy", "clipboard_read", "self_edit", "web_api", "fact_add", "fact_query", "fact_forget", "show_file", "call_status", "call_place", "call_answer", "call_end", "battery", "time", "device_info", "integration_status", "mcp_status",
        "mcp_list_tools", "mcp_call", "mcp_list_resources", "mcp_read_resource", "mcp_list_prompts", "mcp_get_prompt", "record_screen" -> "phone_control"
        "agent_jobs", "agent_cancel", "agent_reassign", "remember", "update_memory", "forget", "recall", "discussion_search", "decision_history", "knowledge_search", "ask_agent", "queue_agent", "specialist_job_status", "undo_last", "undo_status",
        "wait", "task_list", "task_add", "task_update", "task_delete", "skills_list", "ask_user" -> "orion_core"
        else -> null
    }

    private fun allowed(c: Context, pack: String, tool: String): Boolean = true
    fun allowlist(c: Context, pack: String): List<String> = runCatching {
        val a = JSONArray(p(c).getString("allowlist_$pack", "[]") ?: "[]")
        (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.distinct()
    }.getOrDefault(emptyList())
    fun setAllowlist(c: Context, pack: String, tools: List<String>) = Unit
    /** Store privacy-filtered settings and policy events; never persist secrets or raw command payloads. */
    fun log(c: Context, tool: String, action: String, approved: Boolean?, result: String) {
        val safe = AgentPrivacyFilter.safeOutput(result).take(240)
        val safeAction = AgentPrivacyFilter.safeCommand(action).take(80)
        synchronized(this) {
            val old = runCatching { JSONArray(p(c).getString("action_log", "[]") ?: "[]") }.getOrElse { JSONArray() }
            val next = JSONArray()
            for (i in maxOf(0, old.length() - 199) until old.length()) {
                val row = runCatching { old.getJSONObject(i) }.getOrNull() ?: continue
                next.put(row)
            }
            next.put(JSONObject().put("at", System.currentTimeMillis()).put("tool", tool.take(80))
                .put("action", safeAction).put("approved", approved).put("result", safe))
            p(c).edit().putString("action_log", next.toString()).apply()
        }
    }

    fun recentLogs(c: Context, limit: Int = 30): List<String> {
        val rows = runCatching { JSONArray(p(c).getString("action_log", "[]") ?: "[]") }.getOrElse { JSONArray() }
        return (maxOf(0, rows.length() - limit.coerceIn(1, 100)) until rows.length()).mapNotNull { i ->
            runCatching {
                val row = rows.getJSONObject(i)
                val state = if (row.isNull("approved")) "status unknown" else if (row.optBoolean("approved")) "accepted" else "not accepted"
                "${java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(row.optLong("at")))} · ${row.optString("tool")} / ${row.optString("action")} · $state · ${row.optString("result")}"
            }.getOrNull()
        }
    }

}
