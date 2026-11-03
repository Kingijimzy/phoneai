package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Central agent permission policy. Execution adapters must call evaluate() before dispatch. */
object AgentPolicy {
    enum class Mode(val label: String) { PLAN("Plan"), ASK("Ask"), ALLOWLIST("Allowlist"), TRUSTED("Trusted session") }
    enum class Decision { ALLOW, PLAN_ONLY, NEED_APPROVAL, DENY }
    data class Pack(val id: String, val title: String, val description: String, val implemented: Boolean = false)
    data class RuleResult(val decision: Decision, val reason: String)
    val packs = listOf(
        Pack("workspace_shell", "Workspace shell", "SAF workspace files, read/write/patch, allowlisted commands and terminal UI", true),
        Pack("phone_control", "Phone control", "Accessibility actions with act → verify → report", true),
        Pack("termux_bridge", "Termux bridge", "Optional Termux RUN_COMMAND service; requires install, user permission and external-app setting", true),
        Pack("remote_computer", "Remote computer", "Cursor Cloud Agent API; generic sandbox/VPS remains separate", true),
        Pack("connected_services", "Connected services", "GitHub and Gmail read-only access with tokens kept in Orion's encrypted vault; set up on the Capability board", true),
        Pack("messaging", "Messaging", "Telegram, SMS and email; confirmation before sending", true),
        Pack("trading_research", "Trading research", "Public data, coaching, alerts and journal; no auto-orders", true),
        Pack("gaming_hobby", "Gaming & hobbies", "Session notes and light focus presets", true),
        Pack("proactive", "Proactive", "Opt-in local daily task recap notification; no always-on monitoring", true),
        Pack("self_test", "Self-test", "Provider, permission, connectivity and capability checks", true),
        Pack("orion_core", "Orion core", "Local memory, knowledge, tasks, skill discovery and task coordination", true),
        Pack("self_evolution", "Self-evolution", "Proposed skill manifests and diffs; approval before enable", true)
    )
    private fun p(c: Context) = c.getSharedPreferences("phoneai_agent_policy", Context.MODE_PRIVATE)
    fun mode(c: Context): Mode = runCatching { Mode.valueOf(p(c).getString("mode", "ASK") ?: "ASK") }.getOrDefault(Mode.ASK)
    fun setMode(c: Context, v: Mode) { p(c).edit().putString("mode", v.name).apply() }
    /**
     * Packs that are safe to have on from the first launch: they are local, read-only, or already gated by
     * per-action approval. Without them Orion cannot even offer to connect GitHub or Gmail (its connect tool
     * lives in orion_core) and wrongly tells the user the feature does not exist. Risky packs stay opt-in.
     */
    internal val defaultOnPacks = setOf("orion_core", "connected_services", "workspace_shell", "self_test", "gaming_hobby", "trading_research", "phone_control")
    internal fun defaultOn(id: String) = id in defaultOnPacks

    /** An explicit user choice always wins; only a never-set pack falls back to its default. */
    fun enabled(c: Context, id: String) = p(c).getBoolean("pack_$id", defaultOn(id))
    fun setEnabled(c: Context, id: String, on: Boolean) {
        val pack = packs.firstOrNull { it.id == id }
        p(c).edit().putBoolean("pack_$id", on && pack?.implemented == true).apply()
        log(c, "pack:$id", "settings", true, if (on && pack?.implemented == true) "enabled" else if (on) "not implemented; remains off" else "disabled")
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
    fun maxSteps(c: Context) = p(c).getInt("budget_steps", 12).coerceIn(1, 100)
    fun maxCalls(c: Context) = p(c).getInt("budget_calls", 15).coerceIn(1, 100)
    fun maxMinutes(c: Context) = p(c).getInt("budget_minutes", 3).coerceIn(1, 30)
    fun setBudgets(c: Context, steps: Int, calls: Int, minutes: Int) {
        p(c).edit().putInt("budget_steps", steps.coerceIn(1, 100)).putInt("budget_calls", calls.coerceIn(1, 100)).putInt("budget_minutes", minutes.coerceIn(1, 30)).apply()
    }
    /** The one wording for "this pack is off", shared by the pre-flight check, the pack gate and the prompt status. */
    fun packOffMessage(pack: String): String =
        "The '$pack' capability pack is switched off, so this tool is unavailable right now. The feature exists: tell the user which pack to switch on (${ToolGate.howToEnable(pack)}) instead of saying Orion lacks it."

    /** Gate for the agent's dispatch: the same table as every other caller, with the per-tool mode and task memory. */
    internal fun gate(
        c: Context, pack: String, toolId: String, dangerous: Boolean,
        toolMode: ToolMode = ToolMode.ALLOW, askedThisTask: Boolean = true, approvedThisAction: Boolean = false
    ): GatePolicy.Result = GatePolicy.decide(GatePolicy.Input(
        pack = pack,
        packOn = packs.any { it.id == pack } && enabled(c, pack),
        tool = toolId,
        mode = mode(c),
        toolMode = toolMode,
        autonomous = ToolPermissions.level(c) == "autonomous",
        dangerous = dangerous,
        approvedThisAction = approvedThisAction,
        trustedForPack = isTrustedFor(c, pack),
        allowlisted = allowed(c, pack, toolId),
        askedThisTask = askedThisTask
    ))

    /** Existing callers keep their RuleResult shape; the decision itself comes from GatePolicy. */
    fun evaluate(c: Context, pack: String, toolId: String, dangerous: Boolean, approvedThisAction: Boolean = false): RuleResult {
        val r = gate(c, pack, toolId, dangerous, toolMode = ToolPermissions.mode(c, toolId), askedThisTask = true, approvedThisAction = approvedThisAction)
        val decision = when (r.verdict) {
            GatePolicy.Verdict.ALLOW -> Decision.ALLOW
            GatePolicy.Verdict.DENY -> Decision.DENY
            GatePolicy.Verdict.PLAN_ONLY -> Decision.PLAN_ONLY
            GatePolicy.Verdict.ASK_TOOL, GatePolicy.Verdict.NEED_APPROVAL -> Decision.NEED_APPROVAL
        }
        return RuleResult(decision, r.reason)
    }

    /** Maps every built-in tool to exactly one enabled capability pack. Unknown tools fail closed. */
    fun packForTool(tool: String): String? = when (tool) {
        "play", "look" -> "gaming_hobby"
        "termux_command" -> "termux_bridge"
        "shell", "workspace_write", "workspace_read", "python_exec" -> "workspace_shell"
        "graph_link", "graph_query", "create_agent", "self_review", "morning_brief", "connect_service", "service_status", "capability_note", "standing_order", "vault_list", "vault_request", "vault_get" -> "orion_core"
        "github_account_read", "gmail_list_messages" -> "connected_services"
        "trade_history_summary" -> "trading_research"
        "gaming_session_summary", "gaming_session_note_add", "gaming_focus_preset" -> "gaming_hobby"
        "weather", "live_news", "live_web_search", "web_search", "github_public", "fetch_url", "research_web", "bybit_public_ticker" -> "trading_research"
        "telegram_status", "telegram_inbox", "telegram_send", "sms", "notification_action", "resolve_contact" -> "messaging"
        "lookup_app", "open_app", "system_intent", "open_deep_link", "read_notifications", "media_status", "media_control",
        "find_files", "open_file", "list_apps", "list_app_deeplinks", "read_screen", "validate_action", "verify_action",
        "verify_send", "click", "click_text", "wait_for", "tap", "long_press", "swipe", "swipe_path", "scroll", "type",
        "key", "open_url", "browser_snapshot", "browser_find", "browser_extract", "browser_verify", "browser_click",
        "browser_click_verify", "browser_type", "browser_scroll", "browser_navigate", "browser_back", "browser_tabs",
        "browser_upload", "browser_handoff", "browser_wait", "browser_close", "settings", "dial", "alarm", "timer",
        "volume", "flashlight", "copy", "battery", "time", "device_info", "integration_status", "mcp_status",
        "mcp_list_tools", "mcp_call", "record_screen" -> "phone_control"
        "remember", "update_memory", "forget", "recall", "knowledge_search", "ask_agent", "undo_last", "undo_status",
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
    /** Bounded audit trail. Callers must never pass raw secrets. */
    fun log(c: Context, tool: String, action: String, approved: Boolean?, result: String) {
        val safe = Regex("(?i)(api[_ -]?key|token|secret|password)\\s*[:=]\\s*[^\\s,;]+").replace(result) { match -> match.groupValues[1] + "=[redacted]" }.take(240)
        synchronized(this) {
            val old = runCatching { JSONArray(p(c).getString("action_log", "[]") ?: "[]") }.getOrElse { JSONArray() }
            val next = JSONArray()
            for (i in maxOf(0, old.length() - 199) until old.length()) next.put(old.getJSONObject(i))
            next.put(JSONObject().put("at", System.currentTimeMillis()).put("tool", tool.take(80)).put("action", action.take(80)).put("approved", approved).put("result", safe))
            p(c).edit().putString("action_log", next.toString()).apply()
        }
    }
    fun recentLogs(c: Context, limit: Int = 30): List<String> {
        val a = runCatching { JSONArray(p(c).getString("action_log", "[]") ?: "[]") }.getOrElse { JSONArray() }
        return (maxOf(0, a.length() - limit) until a.length()).mapNotNull { i ->
            runCatching {
                val o = a.getJSONObject(i)
                val approved = if (o.isNull("approved")) "approval unknown" else if (o.optBoolean("approved")) "approved" else "not approved"
                "${java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(o.optLong("at")))} · ${o.optString("tool")} / ${o.optString("action")} · $approved · ${o.optString("result")}"
            }.getOrNull()
        }
    }
}
