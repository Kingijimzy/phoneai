package com.phoneai.app

import org.json.JSONObject

/**
 * One dispatch boundary for every capability family.
 *
 * Adapters deliberately do not reimplement backend behavior: the existing hardened executors
 * remain the source of truth for shell/runtime selection, Android actions, browser sessions,
 * MCP pairing, file scope, and skill authorization. This layer gives every registered tool one
 * stable route and a single point for future backend replacement/health instrumentation.
 */
internal object OrionCapabilityAdapters {
    enum class Backend {
        SHELL, PYTHON, FILES, BROWSER, MCP, DEVICE, SKILLS, INTEGRATIONS, COMMUNICATION, MEMORY, TASKS, RESEARCH, UTILITIES, TRADING_READONLY, GAMING, CORE
    }

    data class Route(val backend: Backend, val tool: String)

    private val toolsByBackend: Map<Backend, Set<String>> = mapOf(
        Backend.SHELL to setOf("shell", "termux_command"),
        Backend.PYTHON to setOf(
            "python_exec", "extension_list", "extension_create", "extension_test",
            "extension_enable", "extension_run", "extension_disable"
        ),
        Backend.FILES to setOf("find_files", "open_file", "save_file", "workspace_write", "workspace_read"),
        Backend.BROWSER to setOf(
            "open_url", "browser_snapshot", "browser_find", "browser_extract", "browser_verify",
            "browser_click", "browser_click_verify", "browser_type", "browser_scroll",
            "browser_navigate", "browser_back", "browser_handoff", "browser_wait",
            "browser_tabs", "browser_upload", "browser_close"
        ),
        Backend.MCP to setOf("mcp_status", "mcp_list_tools", "mcp_call"),
        Backend.DEVICE to setOf(
            "lookup_app", "open_app", "system_intent", "open_deep_link", "read_notifications",
            "media_status", "media_control", "play_media", "chrome_search", "resolve_contact",
            "list_apps", "list_app_deeplinks", "read_screen", "validate_action", "verify_action",
            "verify_send", "click", "click_text", "wait_for", "tap", "long_press", "swipe",
            "swipe_path", "scroll", "type", "key", "look", "play", "record_screen"
        ),
        Backend.SKILLS to setOf("skills_list"),
        Backend.INTEGRATIONS to setOf("integration_status", "service_status", "connect_service",
            "github_account_read", "gmail_list_messages", "vault_list", "vault_request", "vault_get", "capability_note"),
        Backend.COMMUNICATION to setOf("telegram_status", "telegram_inbox", "telegram_send", "dial", "sms", "notification_action"),
        Backend.MEMORY to setOf("remember", "update_memory", "forget", "recall", "discussion_search", "decision_history", "record_decision", "knowledge_search",
            "graph_link", "graph_query", "self_review", "undo_last", "undo_status"),
        Backend.TASKS to setOf("ask_agent", "create_agent", "task_list", "task_add", "task_update",
            "task_delete", "ask_user", "morning_brief", "standing_order"),
        Backend.RESEARCH to setOf("web_search", "live_news", "live_web_search", "fetch_url", "research_web",
            "weather", "github_public", "github_search_repositories", "bybit_public_ticker", "live_sports"),
        Backend.UTILITIES to setOf("settings", "alarm", "timer", "volume", "flashlight", "copy",
            "battery", "time", "wait", "device_info", "device_location"),
        Backend.TRADING_READONLY to setOf("trade_history_summary"),
        Backend.GAMING to setOf("gaming_session_summary", "gaming_session_note_add", "gaming_focus_preset")
    )

    private val backendByTool: Map<String, Backend> = buildMap {
        toolsByBackend.forEach { (backend, names) ->
            names.forEach { name ->
                require(put(name, backend) == null) { "Tool '$name' is assigned to more than one capability adapter." }
            }
        }
    }

    /** Resolve only tools that exist in the authoritative tool registry. */
    fun route(tool: String): Route? {
        if (ToolRegistry.find(tool) == null) return null
        val backend = backendByTool[tool] ?: return null
        return Route(backend, tool)
    }

    fun routes(): List<Route> = ToolRegistry.all.mapNotNull { route(it.name) }

    /**
     * The Agent's real tool dispatch enters here. An unknown tool fails closed; all known tools
     * are sent to their established backend implementation after the family route is resolved.
     */
    fun dispatch(tool: String, args: JSONObject, executor: (Route, JSONObject) -> String): String {
        val route = route(tool) ?: return "error: no capability adapter is registered for '$tool'."
        return try {
            executor(route, args)
        } catch (e: Exception) {
            "error: ${route.backend.name.lowercase()} adapter failed for '$tool' (${e.javaClass.simpleName})."
        }
    }
}
