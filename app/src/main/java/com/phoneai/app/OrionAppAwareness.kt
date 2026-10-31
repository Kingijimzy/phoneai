package com.phoneai.app

import android.content.Context

/**
 * A compact, runtime-derived map of Orion's installed architecture.
 * It describes registered components and current local configuration, never inferred integrations
 * or successful execution. The central Agent uses this as its operating map; delegated workers do not.
 */
internal object OrionAppAwareness {
    internal fun domainForTool(name: String): String = when {
        name.startsWith("browser_") || name in setOf("open_url", "chrome_search", "open_deep_link", "live_web_search", "live_news", "fetch_url") -> "Web and navigation"
        name.startsWith("read_screen") || name in setOf("look", "click", "click_text", "tap", "swipe", "swipe_path", "scroll", "type", "key", "play", "validate_action", "verify_action", "verify_send", "wait_for") -> "Screen perception and interaction"
        name.contains("notification") || name.startsWith("media_") || name in setOf("play_media", "record_screen") -> "Notifications and media"
        name.contains("contact") || name in setOf("call", "sms", "send_email", "telegram_send") -> "Communication and contacts"
        name.contains("memory") || name in setOf("remember", "update_memory", "forget", "recall", "knowledge_search", "graph_link", "graph_query", "discussion_search", "decision_history") -> "Memory and knowledge"
        name.contains("task") || name.contains("agent") || name.contains("standing_order") || name.contains("reminder") -> "Tasks and agents"
        name.contains("github") || name.contains("mcp") || name.contains("gmail") || name.contains("service") -> "Connected services and integrations"
        name.contains("python") || name.contains("shell") || name.contains("file") || name.startsWith("extension_") -> "Code, files and local execution"
        name.contains("ticker") || name.contains("market") || name.contains("trade") || name.contains("trading") || name.contains("deriv") -> "Trading and market data"
        name.contains("app") || name.contains("intent") || name.contains("device") || name.contains("battery") || name.contains("location") -> "Android and installed apps"
        else -> "Other registered capabilities"
    }

    internal fun toolArchitecture(tools: List<ToolDefinition>): String =
        tools.groupBy { domainForTool(it.name) }
            .toSortedMap()
            .entries.joinToString("\n") { (domain, rows) ->
                val reads = rows.filter { it.access == ToolAccess.READ }.map { it.name }.sorted()
                val writes = rows.filter { it.access == ToolAccess.WRITE }.map { it.name }.sorted()
                buildString {
                    append("- ").append(domain).append(": ")
                    append("READ [").append(reads.joinToString(", ").ifBlank { "none" }).append("]; ")
                    append("WRITE [").append(writes.joinToString(", ").ifBlank { "none" }).append("]")
                }
            }

    private fun componentInventory(c: Context): String = runCatching {
        // PackageManager reflects components in the installed APK, not a guessed feature catalogue.
        @Suppress("DEPRECATION")
        val info = c.packageManager.getPackageInfo(
            c.packageName,
            android.content.pm.PackageManager.GET_ACTIVITIES or
                android.content.pm.PackageManager.GET_SERVICES or
                android.content.pm.PackageManager.GET_RECEIVERS
        )
        fun shortName(value: String): String = value.substringAfterLast('.')
        val activities = (info.activities ?: emptyArray()).map { shortName(it.name) }.distinct().sorted()
        val services = (info.services ?: emptyArray()).map {
            shortName(it.name) + if (it.enabled) "[manifest-enabled]" else "[manifest-disabled]"
        }.sorted()
        val receivers = (info.receivers ?: emptyArray()).map { shortName(it.name) }.distinct().sorted()
        buildString {
            append("Installed activities/screens (").append(activities.size).append("): ")
                .append(activities.take(36).joinToString(", ").ifBlank { "none" })
            if (activities.size > 36) append(" … +").append(activities.size - 36)
            append("\nInstalled services (").append(services.size).append("): ")
                .append(services.take(24).joinToString(", ").ifBlank { "none" })
            if (services.size > 24) append(" … +").append(services.size - 24)
            append("\nInstalled receivers (").append(receivers.size).append("): ")
                .append(receivers.take(20).joinToString(", ").ifBlank { "none" })
            if (receivers.size > 20) append(" … +").append(receivers.size - 20)
            append("\nIMPORTANT: manifest-enabled means declared/enabled in the installed package; it does not prove a service is running, permission is granted, or a screen can currently launch.")
        }
    }.getOrDefault("Installed component inventory unavailable; do not infer screens or service state.")

    fun context(c: Context): String = runCatching {
        val sb = StringBuilder("ORION APP ARCHITECTURE MAP — generated from installed registries and local state; this is an inventory, not proof that an action succeeded.")
        sb.append("\nCONTROL FLOW: chat/voice → central Agent → intent/task orchestration → registered Tools/Local adapters → ToolGate + permissions + approval → guarded dispatcher → action log and verification. Specialist agents return analysis to the parent; they do not own phone execution.")
        sb.append("\nCORE SUBSYSTEMS: Agent/AgentRunner (conversation and reasoning); ToolRegistry/Tools (tool contracts and adapters); ToolGate/ToolPermissions/AgentPolicy (permission and pack gates); OrionTaskOrchestrator/TaskStore/OrionTasksCapabilityExecutor (task planning and execution); OrionResearchShortcutLifecycle (checkpointed read workflows); OrionRuntime/OrionRecoveryWork (background recovery); MemoryStore/AgentLessons/OrionMemoryCapabilityExecutor (personal memory); KnowledgeStore/KnowledgeGraph (documents and relations); OrionSkillsCapabilityExecutor/SkillRegistry (declarative skills); CapabilityRegistry/OrionCapabilityDiscovery (capability truth); ServiceCatalog/OrionConnectionStore/McpConnector (integrations).")
        sb.append("\nREGISTERED TOOL DOMAINS (actual ToolRegistry entries; READ is not a write grant):\n").append(toolArchitecture(ToolRegistry.all))
        sb.append("\nSPECIALIST AGENTS (actual profiles):\n").append(AgentProfiles.rosterBlock(c))
        val skills = runCatching { OrionSkillsCapabilityExecutor.list(c) }.getOrDefault(emptyList())
        sb.append("\nSKILLS: ").append(if (skills.isEmpty()) "none registered" else skills.joinToString("; ") { it.id + " v" + it.version + " [" + (if (it.enabled) "enabled" else "disabled") + "]" })
        sb.append("\nCAPABILITY PACKS: ").append(AgentPolicy.packs.joinToString("; ") { it.id + "=" + if (AgentPolicy.enabled(c, it.id)) "enabled" else "disabled" })
        sb.append("\nCONNECTED SERVICES (local status only): ")
        sb.append(ServiceCatalog.all.joinToString("; ") { service ->
            val connection = OrionConnectionStore.get(c, service.id)
            service.id + "=" + when {
                connection?.secretAvailable != true -> "not connected"
                connection.verified -> "verified locally"
                else -> "saved, not verified"
            }
        })
        val activeTasks = runCatching { TaskStore.all(c).count { it.status !in setOf("completed", "done", "cancelled", "failed") } }.getOrDefault(-1)
        sb.append("\nLOCAL DATA INVENTORY (counts only): memories=").append(runCatching { MemoryStore.all(c).size }.getOrDefault(-1))
            .append(", knowledge documents=").append(runCatching { KnowledgeStore.list(c).size }.getOrDefault(-1))
            .append(", active tasks=").append(activeTasks)
        sb.append("\nLIVE EXECUTION LIMITS: ").append(runCatching { ToolGate.statusFor(c) }.getOrDefault("tool gate status unavailable").take(1800))
        sb.append("\nMCP: ").append(runCatching { McpConnector.status(c) }.getOrDefault("status unavailable"))
        sb.append("\nOPERATING RULE: use the real registry and live gate before routing; explain unavailable states and exact prerequisites. Never infer an unregistered subsystem, never bypass the guarded dispatcher, and never treat this map as evidence of successful execution.")
        sb.toString().take(10500)
    }.getOrDefault("Orion's app architecture map could not be read. Do not guess at installed components or their availability.")
}
