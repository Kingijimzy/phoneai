package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Central agent permission policy. Execution adapters must call evaluate() before dispatch. */
object AgentPolicy {
    enum class Mode(val label: String) { PLAN("Plan"), ALLOW("Allow") }
    enum class Decision { ALLOW, PLAN_ONLY, DENY }
    data class Pack(val id: String, val title: String, val description: String, val implemented: Boolean = false)
    data class RuleResult(val decision: Decision, val reason: String)
    val packs = listOf(
        Pack("workspace_shell", "Workspace shell", "Owner-authorized shell commands and SAF workspace read/write/patch operations; only catastrophic device-destruction commands are hard-blocked", true),
        Pack("phone_control", "Phone control", "Accessibility actions with act → verify → report", true),
        Pack("termux_bridge", "Termux bridge", "Optional Termux RUN_COMMAND service; requires install, user permission and external-app setting", true),
        Pack("remote_computer", "Remote computer", "Cursor Cloud Agent API; generic sandbox/VPS remains separate", true),
        Pack("connected_services", "Connected services", "GitHub and Gmail read-only access with tokens kept in Orion's encrypted vault; set up on the Capability board", true),
        Pack("messaging", "Messaging", "Telegram, SMS and email; authorized sends execute without duplicate approval", true),
        Pack("trading_research", "Trading research", "Public market data, coaching, risk checks, strategy research and paper trading; real order execution remains disabled", true),
        Pack("gaming_hobby", "Gaming & hobbies", "Session notes and light focus presets", true),
        Pack("proactive", "Proactive", "Opt-in live event-driven Soul watcher and local scheduled task recaps; requires Orion Core and Accessibility Service", true),
        Pack("self_test", "Self-test", "Provider, permission, connectivity and capability checks", true),
        Pack("orion_core", "Orion core", "Local memory, knowledge, tasks, skill discovery and task coordination", true),
        Pack("self_evolution", "Self-evolution", "Generated tools are automatically activated only after exact-source sandbox verification; version history and rollback remain available", true)
    )
    private fun p(c: Context) = c.getSharedPreferences("phoneai_agent_policy", Context.MODE_PRIVATE)
    /** PLAN is the only global execution stop; legacy saved modes resolve to ALLOW. */
    fun mode(c: Context): Mode {
        val saved = p(c).getString("mode", null)
        return if (saved.equals("PLAN", ignoreCase = true)) Mode.PLAN else Mode.ALLOW
    }
    fun setMode(c: Context, v: Mode) {
        p(c).edit().putString("mode", if (v == Mode.PLAN) Mode.PLAN.name else Mode.ALLOW.name).apply()
    }
    /**
     * Core companion packs are enabled on first launch so phone and messaging features are discoverable.
     * This only makes their tools available: Android OS permissions, explicit capability scope and validation
     * still apply. Only restart, shutdown and Wi-Fi toggles require an action confirmation. Optional bridges remain opt-in.
     */
    internal val defaultOnPacks = setOf(
        "orion_core", "connected_services", "workspace_shell", "self_test",
        "gaming_hobby", "trading_research", "phone_control", "messaging"
    )
    internal fun defaultOn(id: String) = id in defaultOnPacks

    /** An explicit user choice always wins; only a never-set pack falls back to its default. */
    @Suppress("UNUSED_PARAMETER") fun enabled(c: Context, id: String) = true // Free mode: every capability pack is on.
    fun setEnabled(c: Context, id: String, on: Boolean) {
        val pack = packs.firstOrNull { it.id == id }
        p(c).edit().putBoolean("pack_$id", on && pack?.implemented == true).apply()
        log(c, "pack:$id", "settings", true, if (on && pack?.implemented == true) "enabled" else if (on) "not implemented; remains off" else "disabled")
        if (id == "orion_core" || id == "proactive") OrionProactiveWork.refresh(c)
    }
    fun trustedUntil(c: Context) = p(c).getLong("trusted_until", 0L)
    fun startTrustedSession(c: Context, minutes: Int, ids: Set<String>) {
        val until = System.currentTimeMillis() + minutes.coerceIn(1, 120) * 60_000L
        val e = p(c).edit().putLong("trusted_until", until)
        packs.forEach { e.putBoolean("trusted_${it.id}", it.id in ids && enabled(c, it.id)) }
        e.apply(); log(c, "trusted-session", "start", true, "timed session started; expiry=$until")
    }
    fun stopTrustedSession(c: Context) { p(c).edit().putLong("trusted_until", 0L).apply(); log(c, "trusted-session", "stop", true, "stopped by user") }
    fun isTrustedFor(c: Context, id: String) = System.currentTimeMillis() < trustedUntil(c) && p(c).getBoolean("trusted_$id", false)
    fun maxSteps(c: Context) = p(c).getInt("budget_steps_v5", 100_000).coerceIn(1, 1_000_000)
    fun maxCalls(c: Context) = p(c).getInt("budget_calls_v5", 100_000).coerceIn(1, 1_000_000)
    fun maxMinutes(c: Context) = p(c).getInt("budget_minutes_v5", 10_080).coerceIn(1, 100_000)
    fun setBudgets(c: Context, steps: Int, calls: Int, minutes: Int) {
        p(c).edit().putInt("budget_steps_v5", steps.coerceIn(1, 1_000_000)).putInt("budget_calls_v5", calls.coerceIn(1, 1_000_000)).putInt("budget_minutes_v5", minutes.coerceIn(1, 100_000)).apply()
    }
    /** The one wording for "this pack is off", shared by the pre-flight check, the pack gate and the prompt status. */
    fun packOffMessage(pack: String): String =
        "The '$pack' capability pack is switched off, so this tool is unavailable right now. The feature exists: tell the user which pack to switch on (${ToolGate.howToEnable(pack)}) instead of saying Orion lacks it."

    /** Gate for the agent's dispatch: the same table as every other caller, with the per-tool mode and task memory. */
    internal fun gate(
        c: Context, pack: String, toolId: String, dangerous: Boolean,
        toolMode: ToolMode = ToolMode.ALLOW, askedThisTask: Boolean = true, approvedThisAction: Boolean = false,
        hardBlockReason: String? = null
    ): GatePolicy.Result {
        val talkReadOnly = OrionAutonomyPolicy.blocksWrite(
            OrionAutonomySettings.read(c).mode,
            ToolRegistry.all.firstOrNull { it.name == toolId }?.access
        )
        val effectiveToolMode = if (talkReadOnly) ToolMode.DENY else toolMode
        return GatePolicy.decide(GatePolicy.Input(
        pack = pack,
        packOn = packs.any { it.id == pack } && enabled(c, pack),
        tool = toolId,
        mode = mode(c),
        toolMode = effectiveToolMode,
        // Legacy per-tool ASK values are normalized to ALLOW by ToolPermissionPolicy.modeOf.
        // Only an explicit DENY, PLAN mode or disabled capability pack blocks dispatch.
        autonomous = ToolPermissions.level(c) == "autonomous",
        dangerous = dangerous,
        approvedThisAction = approvedThisAction,
        trustedForPack = isTrustedFor(c, pack),
        allowlisted = allowed(c, pack, toolId),
        askedThisTask = askedThisTask,
        hardBlockReason = hardBlockReason
        ))
    }

    /** Existing callers keep their RuleResult shape; the decision itself comes from GatePolicy. */
    fun evaluate(c: Context, pack: String, toolId: String, dangerous: Boolean, approvedThisAction: Boolean = false): RuleResult {
        val r = gate(c, pack, toolId, dangerous, toolMode = ToolPermissions.mode(c, toolId), askedThisTask = true, approvedThisAction = approvedThisAction)
        val decision = when (r.verdict) {
            GatePolicy.Verdict.ALLOW -> Decision.ALLOW
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

}
