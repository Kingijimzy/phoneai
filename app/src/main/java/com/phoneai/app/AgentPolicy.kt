package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Central agent permission policy. All tools default to ALLOW; execution adapters call evaluate() before dispatch. */
object AgentPolicy {
    enum class Decision { ALLOW, DENY }
    data class Pack(val id: String, val title: String, val description: String, val implemented: Boolean = false)
    data class RuleResult(val decision: Decision, val reason: String)
    val packs = listOf(
        Pack("workspace_shell", "Workspace shell", "Owner-authorized shell commands and SAF workspace read/write/patch operations", true),
        Pack("phone_control", "Phone control", "Accessibility actions with act → verify → report", true),
        Pack("termux_bridge", "Termux bridge", "Optional Termux RUN_COMMAND service", true),
        Pack("remote_computer", "Remote computer", "Cursor Cloud Agent API", true),
        Pack("connected_services", "Connected services", "GitHub and Gmail read-only access with tokens kept in NEXUS's encrypted vault", true),
        Pack("messaging", "Messaging", "Telegram, SMS and email; authorized sends execute without duplicate approval", true),
        Pack("trading_research", "Trading research", "Public market data, coaching, risk checks, strategy research and paper trading; real order execution remains blocked until Boss approval", true),
        Pack("gaming_hobby", "Gaming & hobbies", "Session notes and light focus presets", true),
        Pack("proactive", "Proactive", "Opt-in live event-driven Soul watcher and local scheduled task recaps", true),
        Pack("self_test", "Self-test", "Provider, permission, connectivity and capability checks", true),
        Pack("nexus_core", "NEXUS core", "Local memory, knowledge, tasks, skill discovery and task coordination", true),
        Pack("self_evolution", "Self-evolution", "Proposed skill manifests and diffs; exact-source sandbox test required", true)
    )
    private fun p(c: Context) = c.getSharedPreferences("phoneai_agent_policy", Context.MODE_PRIVATE)

    /** All packs are enabled by default; only Trading Desk write orders require explicit Boss approval. */
    internal val defaultOnPacks = setOf(
        "workspace_shell", "phone_control", "termux_bridge", "remote_computer",
        "connected_services", "messaging", "trading_research", "gaming_hobby",
        "proactive", "self_test", "nexus_core", "self_evolution"
    )
    internal fun defaultOn(id: String) = id in defaultOnPacks

    /** An explicit user choice always wins; only a never-set pack falls back to its default. */
    fun enabled(c: Context, id: String) = p(c).getBoolean("pack_$id", defaultOn(id))
    fun setEnabled(c: Context, id: String, on: Boolean) {
        val pack = packs.firstOrNull { it.id == id }
        p(c).edit().putBoolean("pack_$id", on && pack?.implemented == true).apply()
    }

    fun maxSteps(c: Context) = p(c).getInt("budget_steps_v2", 30).coerceIn(1, 100)
    fun maxCalls(c: Context) = p(c).getInt("budget_calls_v2", 45).coerceIn(1, 100)
    fun maxMinutes(c: Context) = p(c).getInt("budget_minutes_v2", 10).coerceIn(1, 30)
    fun setBudgets(c: Context, steps: Int, calls: Int, minutes: Int) {
        p(c).edit().putInt("budget_steps_v2", steps.coerceIn(1, 100)).putInt("budget_calls_v2", calls.coerceIn(1, 100)).putInt("budget_minutes_v2", minutes.coerceIn(1, 30)).apply()
    }

    /** The one wording for "this pack is off", shared by the pre-flight gate and the prompt status. */
    fun packOffMessage(pack: String): String =
        "The '$pack' capability pack is switched off, so this tool is unavailable right now. Tell the Boss which pack to switch on."

    /** Gate for the agent's dispatch: all tools default to ALLOW unless explicitly DENY or pack is off. */
    internal fun gate(
        c: Context, pack: String, toolId: String, dangerous: Boolean,
        toolMode: ToolMode = ToolMode.ALLOW, hardBlockReason: String? = null
    ): GatePolicy.Result {
        return GatePolicy.decide(GatePolicy.Input(
            pack = pack,
            packOn = packs.any { it.id == pack } && enabled(c, pack),
            tool = toolId,
            toolMode = toolMode,
            autonomous = true,
            dangerous = dangerous,
            approvedThisAction = false,
            hardBlockReason = hardBlockReason
        ))
    }

    /** Existing callers keep their RuleResult shape; the decision itself comes from GatePolicy. */
    fun evaluate(c: Context, pack: String, toolId: String, dangerous: Boolean, approvedThisAction: Boolean = false): RuleResult {
        val r = gate(c, pack, toolId, dangerous)
        val decision = when (r.verdict) {
            GatePolicy.Verdict.ALLOW -> Decision.ALLOW
            GatePolicy.Verdict.DENY -> Decision.DENY
        }
        return RuleResult(decision, r.reason)
    }

    /** Maps every built-in tool to exactly one capability pack. Unknown tools fail closed. */
    fun packForTool(tool: String): String? = when (tool) {
        "extension_list", "extension_create", "extension_update", "extension_test", "extension_enable", "extension_run", "extension_disable", "extension_history", "extension_rollback" -> "nexus_core"
        "play" -> "gaming_hobby"
        "termux_command" -> "termux_bridge"
        "shell", "workspace_list", "workspace_write", "workspace_read", "workspace_delete", "workspace_move", "python_exec", "project_create", "project_list", "project_inspect", "project_plan", "project_verify" -> "workspace_shell"
        "self_diagnose", "repair_apply", "graph_link", "graph_query", "create_agent", "self_review", "morning_brief", "connect_service", "service_status", "capability_note", "standing_order", "vault_list", "vault_request", "vault_get",
        "goal_add", "goal_list", "goal_remove", "goal_toggle" -> "nexus_core"
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
        "volume", "flashlight", "copy", "battery", "time", "device_info", "integration_status", "mcp_status",
        "mcp_list_tools", "mcp_call", "mcp_list_resources", "mcp_read_resource", "mcp_list_prompts", "mcp_get_prompt", "record_screen" -> "phone_control"
        "remember", "update_memory", "forget", "recall", "discussion_search", "decision_history", "knowledge_search", "ask_agent", "queue_agent", "specialist_job_status", "undo_last", "undo_status",
        "wait", "task_list", "task_add", "task_update", "task_delete", "skills_list", "ask_user" -> "nexus_core"
        else -> null
    }

    private fun allowed(c: Context, pack: String, tool: String): Boolean = runCatching {
        val a = JSONArray(p(c).getString("allowlist_$pack", "[]") ?: "[]")
        (0 until a.length()).any { a.optString(it) == tool }
    }.getOrDefault(false)
    fun allowlist(c: Context, pack: String): List<String> = runCatching {
        val a = JSONArray(p(c).getString("allowlist_$pack", "[]") ?: "[]")
        (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.distinct()
    }.getOrDefault(emptyList())
    fun setAllowlist(c: Context, pack: String, tools: List<String>) {
        val a = JSONArray()
        tools.filter { it.matches(Regex("[a-zA-Z0-9_.:-]{1,80}")) }.distinct().take(100).forEach { a.put(it) }
        p(c).edit().putString("allowlist_$pack", a.toString()).apply()
    }
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

    /** Trusted session management for AgentControlActivity UI. */
    fun stopTrustedSession(c: Context) {
        p(c).edit().putLong("trusted_until", 0).apply()
    }

    fun startTrustedSession(c: Context, minutes: Int, packs: Set<String>) {
        p(c).edit().putLong("trusted_until", System.currentTimeMillis() + minutes * 60_000L).putString("trusted_packs", packs.joinToString(",")).apply()
    }

    fun trustedUntil(c: Context): Long = p(c).getLong("trusted_until", 0L)

    fun mode(c: Context): String = ToolPermissions.level(c)
}