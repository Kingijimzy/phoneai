package com.phoneai.app

/**
 * Live inventory adapter: ToolRegistry defines what exists; runtime facts decide whether it is usable;
 * CapabilityRegistry is the single authority that derives the state. Never infer capability from prompt text.
 */
internal object OrionCapabilityDiscovery {
    enum class PermissionState { GRANTED, MISSING, UNKNOWN }

    data class ToolFact(
        val name: String,
        val access: ToolAccess,
        val entry: CapabilityRegistry.Entry
    ) {
        val available: Boolean get() = entry.usable
        val reason: String get() = entry.reason
        val state: CapabilityRegistry.State get() = entry.state
    }

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
            val available = tools.filter { it.available }.joinToString { "${it.name}(${it.access.name.lowercase()},${it.state.name.lowercase()})" }
            lines += "Available registered tools: ${available.ifBlank { "none" }}."
            val blocked = tools.filterNot { it.available }.take(16)
            if (blocked.isNotEmpty()) {
                lines += "Unavailable tool blockers: " + blocked.joinToString("; ") { "${it.name}: ${it.state.name.lowercase()} — ${it.reason}" } + "."
            }
            if (blockers.isNotEmpty()) lines += "Runtime blockers: " + blockers.joinToString("; ") + "."
            if (integrationStates.isNotEmpty()) {
                lines += "Observed integration states: " + integrationStates.entries.joinToString("; ") { "${it.key}=${it.value}" } + "."
            }
            lines += "Capability rule: only registered capabilities whose registry state is available or available_limited may be selected. Unknown permission or connection state is not permission to act. Verify each action with observable output."
            lines += "GRANTED CAPABILITY RULE: when a tool is registered, its pack is enabled, and its declared prerequisites are satisfied, use it fully for the requested task. Do not invent extra application-level restrictions, silently narrow its documented functions, or ask for duplicate consent. Still honor actual Android OS/runtime permissions, real connection/authentication requirements, explicit confirmations required for consequential actions, cancellation, and tool errors. If unavailable, name the exact unmet prerequisite and continue with a compatible registered alternative when possible."
            return lines
        }
    }

    private val permissionRequirements = mapOf(
        "read_notifications" to "notification_access",
        "media_status" to "notification_access",
        "media_control" to "notification_access",
        "resolve_contact" to "contacts",
        "device_location" to "location"
    )

    fun discover(
        registeredTools: Collection<ToolDefinition>,
        enabledPacks: Set<String>,
        termuxReady: Boolean,
        permissionStates: Map<String, PermissionState> = emptyMap(),
        integrationStates: Map<String, String> = emptyMap(),
        deniedTools: Set<String> = emptySet()
    ): Snapshot {
        // Duplicate registrations are a programming error: do not silently pick one contract.
        val duplicateNames = registeredTools.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        val unique = registeredTools.distinctBy { it.name }
        val facts = unique.map { tool ->
            val pack = AgentPolicy.packForTool(tool.name)
            val permissionKey = permissionRequirements[tool.name]
            val permissionState = permissionKey?.let { permissionStates[it] ?: PermissionState.UNKNOWN }
            val setupReason = when {
                tool.name == "mcp_list_tools" && integrationStates["mcp_endpoint"] != "configured" ->
                    "no MCP endpoint configured; configure a remote MCP server in Connected services"
                tool.name == "mcp_call" && integrationStates["mcp_endpoint"] != "configured" ->
                    "no MCP endpoint configured"
                tool.name == "mcp_call" && integrationStates["mcp_pairing"] != "paired" ->
                    "MCP endpoint is not locally paired"
                tool.name == "termux_command" && !termuxReady ->
                    "Termux bridge is not ready"
                else -> null
            }
            val permissionReason = when (permissionState) {
                PermissionState.GRANTED -> null
                PermissionState.MISSING -> "required Android permission '$permissionKey' is missing"
                PermissionState.UNKNOWN -> "required Android permission '$permissionKey' is unverified"
                null -> null
            }
            val disabledReason = when {
                tool.name in deniedTools -> "user explicitly set this tool to DENY in Tool permissions"
                pack == null -> null
                pack !in enabledPacks -> "capability pack '$pack' is disabled"
                else -> null
            }
            val entry = CapabilityRegistry.evaluate(
                CapabilityRegistry.Facts(
                    id = tool.name,
                    title = tool.name,
                    source = CapabilityRegistry.Source.BUILT_IN,
                    supported = pack != null,
                    enabledByUser = disabledReason == null,
                    permissionNeeded = permissionReason,
                    setupNeeded = setupReason,
                    health = CapabilityRegistry.Health.UNKNOWN,
                    limitation = disabledReason ?: if (pack == null) "fail closed: tool is not mapped in AgentPolicy.packForTool" else null
                )
            )
            ToolFact(tool.name, tool.access, entry)
        }
        val blockers = mutableListOf<String>()
        if (unique.isEmpty()) blockers += "tool registry is empty"
        if (duplicateNames.isNotEmpty()) blockers += "duplicate tool registrations: ${duplicateNames.sorted().joinToString(", ")}"
        if (enabledPacks.isEmpty()) blockers += "no capability packs are enabled"
        if (unique.any { it.name == "termux_command" } && !termuxReady) blockers += "optional Termux-only command tool unavailable; Orion built-in shell does not depend on Termux"
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
