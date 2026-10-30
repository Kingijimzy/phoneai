package com.phoneai.app

import org.json.JSONObject

/**
 * Owns remote MCP connection, discovery and invocation.
 *
 * Every invocation rechecks local pairing and granted scope here and again in McpConnector.
 * Non-read scopes require an action-specific confirmation. Remote tool calls are never retried
 * after an ambiguous outcome; only the connector's handshake/catalogue methods may reconnect.
 */
internal object OrionMcpCapabilityExecutor {
    fun execute(
        tool: String,
        args: JSONObject,
        service: PhoneAiService,
        tools: Tools,
        confirm: (String) -> Boolean
    ): String = when (tool) {
        "mcp_status" -> tools.mcpStatus()
        "mcp_list_tools" -> tools.mcpListTools()
        "mcp_call" -> callTool(args, service, tools, confirm)
        else -> "error: MCP executor does not implement '$tool'."
    }

    private fun callTool(
        args: JSONObject,
        service: PhoneAiService,
        tools: Tools,
        confirm: (String) -> Boolean
    ): String {
        val name = args.optString("name").trim()
        val remoteArguments = args.optJSONObject("arguments") ?: JSONObject()
        if (!McpConnector.configured(service)) {
            return "error: no MCP endpoint configured. Open Orion Settings → Configure remote MCP server."
        }
        if (!McpConnector.isPaired(service)) {
            return "error: MCP endpoint is not locally paired. Pair it in Orion Settings before making remote calls."
        }
        if (name.isBlank()) return "error: an exact advertised MCP tool name is required."
        val scope = McpConnector.scopeForTool(name)
        if (scope !in McpConnector.scopes(service)) {
            return "error: MCP tool '$name' requires the '$scope' scope, which is disabled."
        }
        if (scope == "read") return tools.mcpCall(name, remoteArguments)

        val host = runCatching { java.net.URI(McpConnector.endpoint(service)).host ?: "unknown host" }
            .getOrDefault("unknown host")
        val approved = confirm(
            "Remote MCP server: $host. Tool: $name. Scope: $scope. Arguments to send: " +
                remoteArguments.toString().take(1200) + ". Approve this remote action?"
        )
        if (!approved) return "error: user cancelled MCP tool call"
        return tools.mcpCall(name, remoteArguments)
    }
}
