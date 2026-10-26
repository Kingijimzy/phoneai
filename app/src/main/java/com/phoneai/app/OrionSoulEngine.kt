package com.phoneai.app

import android.content.Context

/**
 * Orion's lightweight "soul" / executive layer. It does not replace the selected AI model:
 * it supplies verified local context so chat and tool-planning share one view of capabilities,
 * saved connections, enabled skills, and what remains blocked.
 */
internal object OrionSoulEngine {
    fun contextFor(context: Context, request: String): String {
        val tools = try { ToolRegistry.names().toSet() } catch (_: Exception) { emptySet() }
        val skills = try { SkillRegistry.list(context) } catch (_: Exception) { emptyList() }
        val enabledSkills = skills.filter { it.enabled }
        val planner = try {
            OrionIntegrationPlanner.planFor(context, request, integrationCapabilities(tools))
        } catch (_: Exception) { null }

        val lines = mutableListOf<String>()
        lines += "ORION SOUL ENGINE — LIVE LOCAL CONTEXT"
        lines += "This is a capability-aware executive layer connected to the same AI chat and tool agent, not a separate chatbot."
        lines += "Registered tool count: ${tools.size}. Enabled skills: ${enabledSkills.joinToString { it.id }.ifBlank { "none" }}."
        lines += "Operating loop: understand the user's outcome -> inspect actual tools/skills/connection state -> select a supported path -> obtain only required authorization -> act through registered tools -> inspect results -> report verified completion or the precise blocker."
        lines += "Never pretend a provider is connected because its name is recognized. Never ask for passwords, PINs, one-time codes, or raw tokens in chat. Use official authorization screens and store only provider-issued credentials in the encrypted vault. Keep the task context and resume after authorization returns."
        lines += "If capability is missing, research official documentation and prepare a tested, minimal app implementation; do not hand the user a manual installation checklist when the agent can safely do the work. Do not execute downloaded scripts, APKs, or arbitrary skill code. New permissions, account access, installs, external writes, and destructive actions require the relevant user approval."
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
        return lines.joinToString("\n").take(3500)
    }

    private fun integrationCapabilities(tools: Set<String>): Set<String> {
        val capabilities = mutableSetOf<String>()
        if (tools.any { it.contains("github", true) || it.startsWith("mcp_") }) capabilities += "github.account.read"
        if (tools.any { it.contains("gmail", true) || it.contains("email", true) }) capabilities += "gmail.messages.read"
        if (tools.any { it.contains("drive", true) }) capabilities += "google_drive.files.read"
        if (tools.any { it.contains("telegram", true) }) capabilities += "telegram.messages"
        if (tools.any { it.contains("termux", true) || it.contains("shell", true) }) capabilities += "shell.execute"
        return capabilities
    }
}
