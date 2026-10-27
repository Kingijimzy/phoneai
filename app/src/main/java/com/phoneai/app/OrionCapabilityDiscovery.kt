package com.phoneai.app

/**
 * Deterministic runtime inventory. It reports only registered tools and explicit runtime facts;
 * names in prompts, model catalogs, or user requests never count as capabilities.
 */
internal object OrionCapabilityDiscovery {
    enum class PermissionState { GRANTED, MISSING, UNKNOWN }

    data class ToolFact(
        val name: String,
        val access: ToolAccess,
        val available: Boolean,
        val reason: String
    )

    data class Snapshot(
        val registeredCount: Int,
        val availableCount: Int,
        val tools: List<ToolFact>,
        val blockers: List<String>,
        val integrationStates: Map<String, String>
    ) {
        fun promptLines(): List<String> {
            val lines = mutableListOf<String>()
            lines += "LIVE CAPABILITY DISCOVERY: registered=$registeredCount; currently available=$availableCount."
            val available = tools.filter { it.available }.joinToString { "${it.name}(${it.access.name.lowercase()})" }
            lines += "Available registered tools: ${available.ifBlank { "none" }}."
            val blocked = tools.filterNot { it.available }.take(12)
            if (blocked.isNotEmpty()) {
                lines += "Unavailable tool blockers: " + blocked.joinToString("; ") { "${it.name}: ${it.reason}" } + "."
            }
            if (blockers.isNotEmpty()) lines += "Runtime blockers: " + blockers.joinToString("; ") + "."
            if (integrationStates.isNotEmpty()) {
                lines += "Observed integration states: " + integrationStates.entries.joinToString("; ") { "${it.key}=${it.value}" } + "."
            }
            lines += "Capability rule: only available registered tools may be selected. Unknown permission or connection state is not permission to act. Verify each action with observable output."
            return lines
        }
    }

    private val permissionRequirements = mapOf(
        "read_notifications" to "notification_access",
        "media_status" to "notification_access",
        "media_control" to "notification_access",
        "resolve_contact" to "contacts"
    )

    fun discover(
        registeredTools: Collection<ToolDefinition>,
        enabledPacks: Set<String>,
        termuxReady: Boolean,
        permissionStates: Map<String, PermissionState> = emptyMap(),
        integrationStates: Map<String, String> = emptyMap()
    ): Snapshot {
        val unique = registeredTools.distinctBy { it.name }
        val facts = unique.map { tool ->
            val pack = AgentPolicy.packForTool(tool.name)
            val reason = when {
                pack == null -> "no capability-pack mapping; fail closed"
                pack !in enabledPacks -> "capability pack '$pack' is disabled"
                tool.name == "termux_command" && !termuxReady -> "Termux bridge is not ready"
                permissionRequirements[tool.name] != null &&
                    permissionStates[permissionRequirements.getValue(tool.name)] == PermissionState.MISSING ->
                    "required Android permission '${permissionRequirements.getValue(tool.name)}' is missing"
                permissionRequirements[tool.name] != null &&
                    permissionStates[permissionRequirements.getValue(tool.name)] != PermissionState.GRANTED ->
                    "required Android permission '${permissionRequirements.getValue(tool.name)}' is unverified"
                else -> null
            }
            ToolFact(tool.name, tool.access, reason == null, reason ?: "registered, pack enabled, and known prerequisites satisfied")
        }
        val blockers = mutableListOf<String>()
        if (unique.isEmpty()) blockers += "tool registry is empty"
        if (enabledPacks.isEmpty()) blockers += "no capability packs are enabled"
        if (unique.any { it.name == "termux_command" } && !termuxReady) blockers += "shell execution unavailable until the Termux bridge is ready"
        if (facts.none { it.available }) blockers += "no registered tool is currently available"
        return Snapshot(
            registeredCount = unique.size,
            availableCount = facts.count { it.available },
            tools = facts,
            blockers = blockers,
            integrationStates = integrationStates.toMap()
        )
    }
}
