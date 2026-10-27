package com.phoneai.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Orion's lightweight "soul" / executive layer. It does not replace the selected AI model:
 * it supplies verified local context so chat and tool-planning share one view of capabilities,
 * verified connections, enabled skills, and what remains blocked.
 */
internal object OrionSoulEngine {
    fun contextFor(context: Context, request: String): String {
        val registeredTools = try { ToolRegistry.names().toSet() } catch (_: Exception) { emptySet() }
        val enabledPacks = AgentPolicy.packs.filter { AgentPolicy.enabled(context, it.id) }.map { it.id }.toSet()
        val enabledTools = registeredTools.filter { name ->
            val pack = AgentPolicy.packForTool(name)
            pack != null && pack in enabledPacks
        }.toSet()
        val notificationAccess = runCatching {
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?.contains(context.packageName) == true
        }.getOrDefault(false)
        val contactsPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        val skills = try { SkillRegistry.list(context) } catch (_: Exception) { emptyList() }
        val enabledSkills = skills.filter { it.enabled }
        val planner = try {
            OrionIntegrationPlanner.planFor(
                context, request,
                OrionIntegrationCapabilityPolicy.capabilities(enabledTools)
            )
        } catch (_: Exception) { null }

        val capabilitySnapshot = OrionCapabilityDiscovery.discover(
            registeredTools = ToolRegistry.all,
            enabledPacks = enabledPacks,
            permissionStates = mapOf(
                "screen_access_disclosure" to if (Prefs.accessibilityDisclosureAccepted(context))
                    OrionCapabilityDiscovery.PermissionState.GRANTED else OrionCapabilityDiscovery.PermissionState.MISSING,
                "notification_access" to if (notificationAccess)
                    OrionCapabilityDiscovery.PermissionState.GRANTED else OrionCapabilityDiscovery.PermissionState.MISSING,
                "contacts" to if (contactsPermission)
                    OrionCapabilityDiscovery.PermissionState.GRANTED else OrionCapabilityDiscovery.PermissionState.MISSING
            ),
            integrationStates = planner?.let {
                mapOf(it.provider.name.lowercase() to it.state.name.lowercase().replace('_', '-'))
            } ?: emptyMap()
        )

        val executionReadiness = OrionExecutionReadiness.plan(
            goal = OrionGoalUnderstanding.understand(request),
            capabilities = capabilitySnapshot,
            integration = planner
        )
        val lines = mutableListOf<String>()
        lines += "ORION SOUL ENGINE — LIVE LOCAL CONTEXT"
        lines += OrionGoalUnderstanding.promptLine(request)
        lines += "GOAL UNDERSTANDING RULES: treat short follow-ups such as continue, go ahead, do it, and try again as referring to the active conversation task when context exists. Keep greetings and small talk conversational without tool calls. For real tasks, identify the outcome and target, inspect available capabilities, execute only through registered tools, then verify observable results. Ask one focused question only when a necessary target is genuinely ambiguous. A recognized goal never grants account access or approval."
        lines += "This is a capability-aware executive layer connected to the same AI chat and tool agent, not a separate chatbot."
        lines += "Registered tools: ${registeredTools.size}; tools currently usable after pack and permission checks: ${capabilitySnapshot.availableCount}. Enabled skills: ${enabledSkills.joinToString { it.id }.ifBlank { "none" }}."
        lines += capabilitySnapshot.promptLines()
        lines += executionReadiness.promptLines()
        lines += "Operating loop: understand the user's outcome -> inspect actual tools/skills/connection state -> select a supported path -> obtain only required authorization -> act through registered tools -> inspect results -> report verified completion or the precise blocker."
        lines += "Never pretend a provider is connected because its name is recognized. Never ask for passwords, PINs, one-time codes, or raw tokens in chat. Use official authorization screens and store only provider-issued credentials in the encrypted vault. Keep the task context and resume after authorization returns."
        lines += "If capability is missing, inspect the real tool/skill registry and available code/build workspace first. Research official documentation only through an available research tool. Modify source or run builds only when a connected workspace provides those operations; distinguish proposed changes from applied changes, passing tests/builds, and installed releases. Never claim the installed app can rebuild or modify itself without an authorized code/build tool. Do not execute downloaded scripts, APKs, or arbitrary skill code. New permissions, account access, installs, external writes, and destructive actions require the relevant user approval."
        if (planner != null) {
            lines += "Integration intent: ${planner.provider}; observed state: ${planner.state}; next step: ${planner.nextStep}; required capabilities: ${planner.requiredCapabilities.joinToString().ifBlank { "none identified" }}."
            lines += "Integration assessment: ${planner.explanation}"
            if (planner.nextStep == OrionIntegrationPlanner.NextStep.ASK_TO_CONNECT) {
                lines += "If the user is asking to use this provider now, begin or offer the official connection flow through the app's supported authorization UI; do not merely explain how to connect it."
            }
            if (planner.nextStep == OrionIntegrationPlanner.NextStep.RESEARCH_AND_PROPOSE ||
                planner.nextStep == OrionIntegrationPlanner.NextStep.ASK_TO_ENABLE_OR_INSTALL) {
                lines += "Do not claim the provider action can already run. Check the live registry and source architecture, research the official API, then implement/test the supported missing capability or state the concrete blocker."
            }
        }
        if (enabledSkills.isNotEmpty()) {
            lines += "Potential skills for discovery (not automatic permission grants): " +
                enabledSkills.joinToString { skill -> "${skill.id} [tools=${skill.allowedTools.joinToString()}]" }
        }
        lines += SoulProtocol.serviceLines(
            ServiceCatalog.all.map { service ->
                val connection = OrionConnectionStore.get(context, service.id)
                service.title to when {
                    connection?.secretAvailable == true && connection.verified -> "connected"
                    connection?.secretAvailable == true -> "saved but not verified"
                    else -> "not connected"
                }
            },
            AgentPolicy.enabled(context, "connected_services"),
            runCatching { CapabilityBoard.open(context).map { it.goal } }.getOrDefault(emptyList())
        )
        return lines.joinToString("\n").take(5000)
    }

}
