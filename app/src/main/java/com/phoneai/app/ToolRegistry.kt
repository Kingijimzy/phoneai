package com.phoneai.app

/** The single source of truth for built-in agent tool contracts and access classes. */
internal enum class ToolAccess { READ, WRITE }

internal data class ToolDefinition(
    val name: String,
    val promptLine: String,
    val description: String,
    val access: ToolAccess
)

internal object ToolRegistry {
    private fun tool(name: String, access: ToolAccess, contract: String): ToolDefinition {
        require(contract.startsWith(name)) { "Tool prompt contract must begin with its registered name: $name" }
        return ToolDefinition(name, contract, contract.substringAfter(": ", contract).take(280), access)
    }

    val all: List<ToolDefinition> = listOf(
        tool("lookup_app", ToolAccess.WRITE, "lookup_app {name}: resolve a launchable installed app to its exact label and package before opening it"),
    )
}
