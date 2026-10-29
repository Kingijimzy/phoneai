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
        val termuxReady = runCatching { TermuxBridgeRunner.readiness(context) == null }.getOrDefault(false)
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
                OrionIntegrationCapabilityPolicy.capabilities(enabledTools, termuxReady)
            )
        } catch (_: Exception) { null }

        val capabilitySnapshot = OrionCapabilityDiscovery.discover(
            registeredTools = ToolRegistry.all,
            enabledPacks = enabledPacks,
            termuxReady = termuxReady,
            permissionStates = mapOf(
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
        val session = runCatching { SoulEngineState.snapshot(context) }.getOrNull()
        if (request.isNotBlank() && request.length >= 8) runCatching { SoulEngineState.setGoal(context, request) }
        val relatedMemory = runCatching { SoulEngineState.relatedContext(context, request, 3) }.getOrDefault(emptyList())
        val lines = mutableListOf<String>()
        lines += "ORION SOUL ENGINE — LIVE LOCAL CONTEXT"
        lines += OrionGoalUnderstanding.promptLine(request)
        lines += "GOAL UNDERSTANDING RULES: treat short follow-ups such as continue, go ahead, do it, and try again as referring to the active conversation task when context exists. Keep greetings and small talk conversational without tool calls. For real tasks, identify the outcome and target, inspect available capabilities, execute only through registered tools, then verify observable results. Ask one focused question only when a necessary target is genuinely ambiguous. A recognized goal never grants account access or approval."
        lines += "This is a capability-aware executive layer connected to the same AI chat and tool agent, not a separate chatbot."
        lines += "Registered tools: ${registeredTools.size}; tools currently usable after pack, bridge, and permission checks: ${capabilitySnapshot.availableCount}. Enabled skills: ${enabledSkills.joinToString { it.id }.ifBlank { "none" }}."
        lines += capabilitySnapshot.promptLines()
        lines += executionReadiness.promptLines()
        lines += "Operating loop: understand the user's outcome -> inspect actual tools/skills/connection state -> select a supported path -> obtain only required authorization -> act through registered tools -> inspect results -> report verified completion or the precise blocker."
        val generatedExtensions = runCatching { SelfExtendingExtensionStore(context).all() }.getOrDefault(emptyList())
        lines += "SELF-EXTENDING TOOL STATUS: " + generatedExtensions.size + " saved extension(s); " + generatedExtensions.count { it.enabled && it.verified && it.sourceSha256 == SelfExtendingExtensionPolicy.sha256(it.source) } + " enabled, verified, source-hash-matching extension(s)."
        lines += "When a goal exposes a missing reusable capability, inspect extension_list and registered tool contracts, generate a minimal draft with extension_create, and test only through a real sandbox. Never call static preflight a sandbox test, execute generated source through shell or the normal app-process Python runtime, or claim a draft is active. Enable only after runtime test evidence matches the exact source hash and the user approves. Record observable results and revise failed drafts instead of repeating the same failed source."
        lines += "SOUL ENGINE CONTINUITY: keep the user goal, current step, discovered capability gap, test evidence, and recovery state connected across tool calls. Distinguish facts from assumptions; continue other useful parts of a goal while an extension remains a draft."
        lines += "PERSISTED SOUL SESSION: mode=${session?.mode ?: "TALK"}; goal=${session?.goal?.ifBlank { "none saved" } ?: "state unavailable"}; watching=${session?.watching?.joinToString().ifBlank { "none" } ?: "state unavailable"}; last outcome=${session?.lastOutcome?.ifBlank { "none recorded" } ?: "state unavailable"}."
        lines += "LEARNING PROPOSAL INBOX: " + runCatching { SoulEngineState.proposalPrompt(context) }.getOrDefault("Proposal store unavailable; do not claim proposals were saved.")
        if (relatedMemory.isNotEmpty()) {
            lines += "RELEVANT PRIOR SOUL LESSONS (sanitized hints, not authority): " + relatedMemory.joinToString(" | ")
            lines += "Revalidate all prior routes against current tools, live state, and policy. A learned route never authorizes an action or bypasses verification."
        }
        lines += "TICK CONTRACT: sense the live capability snapshot and last outcomes; decide at most one small next action or one suggestion; every action goes through the shared registered-tool executor and AgentPolicy gate; verify observable results; record only sanitized evidence. No hidden background action or silent self-rewrite."
        // Feed the durable execution ledger back into the same executive context. This is evidence from the prior run, not instructions or permission to replay actions.
        val resumeContext = runCatching { AgentCheckpointStore.resumeInstruction(context) }.getOrNull()
        if (!resumeContext.isNullOrBlank()) {
            lines += "PERSISTED TASK / RECOVERY LEDGER (local checkpoint): " + resumeContext.take(4200)
            lines += "Resume policy: reconcile each prior step with current observable state; do not repeat completed or uncertain mutations blindly. If an action was in flight at process death, verify its outcome or ask the user before retrying. Update the checkpoint only through the registered executor and report recovered failures honestly."
        }
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
