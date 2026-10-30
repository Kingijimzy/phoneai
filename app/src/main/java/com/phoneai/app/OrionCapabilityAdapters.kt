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
        SHELL, PYTHON, FILES, BROWSER, MCP, DEVICE, SKILLS, CORE
    }

    data class Route(val backend: Backend, val tool: String)

    private val toolsByBackend: Map<Backend, Set<String>> = mapOf(
        Backend.SHELL to setOf("shell", "termux_command"),
        Backend.PYTHON to setOf(
            "python_exec", "extension_list", "extension_create", "extension_test",
            "extension_enable", "extension_run", "extension_disable"
        ),
        Backend.FILES to setOf("find_files", "open_file", "workspace_write", "workspace_read"),
        Backend.BROWSER to setOf(
            "open_url", "browser_snapshot", "browser_find", "browser_extract", "browser_verify",
            "browser_click", "browser_click_verify", "browser_type", "browser_scroll",
            "browser_navigate", "browser_back", "browser_handoff", "browser_wait",
            "browser_tabs", "browser_upload", "browser_close", "web_search",
            "live_web_search", "live_news", "fetch_url", "research_web"
        ),
        Backend.MCP to setOf("mcp_status", "mcp_list_tools", "mcp_call"),
        Backend.DEVICE to setOf(
            "lookup_app", "open_app", "system_intent", "open_deep_link", "read_notifications",
            "notification_action", "media_status", "media_control", "resolve_contact",
            "list_apps", "list_app_deeplinks", "read_screen", "validate_action", "verify_action",
            "verify_send", "click", "click_text", "wait_for", "tap", "long_press", "swipe",
            "swipe_path", "scroll", "type", "key", "look", "play", "device_location",
            "dial", "sms", "alarm", "timer", "volume", "flashlight", "copy", "record_screen"
        ),
        Backend.SKILLS to setOf("skills_list")
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
        return Route(backendByTool[tool] ?: Backend.CORE, tool)
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
