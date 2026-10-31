package com.phoneai.app

import android.content.Context

/** Local, provider-free health inspection for the central brain. */
internal data class OrionDiagnosticReport(
    val generatedAtEpochMs: Long,
    val findings: List<OrionDiagnosticFinding>,
    val registeredToolCount: Int,
    val enabledSkillCount: Int,
    val enabledVerifiedExtensionCount: Int,
    val runtimeState: String,
    val queuedBackgroundWork: Int
) {
    val healthy: Boolean get() = findings.none {
        it.severity == OrionFindingSeverity.ERROR || it.severity == OrionFindingSeverity.CRITICAL
    }

    fun promptBlock(): String = buildString {
        appendLine("ORION LOCAL SELF-DIAGNOSTIC — ${if (healthy) "NO STRUCTURAL ERROR DETECTED" else "ISSUES NEED ATTENTION"}")
        appendLine("generated_at=$generatedAtEpochMs; registered_tools=$registeredToolCount; enabled_skills=$enabledSkillCount; enabled_verified_extensions=$enabledVerifiedExtensionCount; runtime=$runtimeState; queued_background_work=$queuedBackgroundWork.")
        appendLine("Scope: local registry, manifest, extension-integrity, checkpoint and queue checks only. This does not prove provider/network health, Android permission grants, device reliability, or successful external actions.")
        if (findings.isEmpty()) appendLine("No local structural findings.")
        findings.take(12).forEach { finding ->
            appendLine("- [${finding.severity}] ${finding.component}: ${finding.title} — ${finding.detail.take(220)}; evidence=${finding.evidenceRefs.joinToString().take(180)}")
        }
        appendLine("Recovery rule: investigate findings using current evidence. Never change permissions or privileged policy automatically; never replay an uncertain write; verify any repair with the relevant real test.")
    }.take(3_000)
}

internal object OrionSelfDiagnostics {
    fun inspect(context: Context, now: Long = System.currentTimeMillis()): OrionDiagnosticReport {
        val app = context.applicationContext
        val findings = mutableListOf<OrionDiagnosticFinding>()
        fun add(component: String, title: String, detail: String, severity: OrionFindingSeverity, evidence: String) {
            val safeComponent = component.take(60)
            val safeEvidence = evidence.take(180)
            val id = "diag-" + safeComponent.lowercase().replace(Regex("[^a-z0-9]+"), "-") + "-" + detail.hashCode().toUInt().toString(16)
            findings += OrionDiagnosticFinding(
                id = id.take(100), component = safeComponent, title = title.take(100),
                detail = detail.replace(Regex("\\s+"), " ").take(300), severity = severity,
                evidenceRefs = listOf(safeEvidence), detectedAtEpochMs = now
            )
        }

        val tools = runCatching { ToolRegistry.all }.getOrDefault(emptyList())
        val duplicateNames = tools.groupBy { it.name }.filterValues { it.size > 1 }.keys
        duplicateNames.forEach { add("tool_registry", "Duplicate tool registration", "Tool '$it' is registered more than once.", OrionFindingSeverity.CRITICAL, "ToolRegistry:$it") }

        val declaredPacks = AgentPolicy.packs.map { it.id }.toSet()
        tools.forEach { tool ->
            val pack = runCatching { AgentPolicy.packForTool(tool.name) }.getOrNull()
            when {
                pack == null -> add("tool_registry", "Tool has no capability pack", "Registered tool '${tool.name}' has no policy pack mapping.", OrionFindingSeverity.ERROR, "ToolRegistry:${tool.name}")
                pack !in declaredPacks -> add("agent_policy", "Tool maps to unknown pack", "Tool '${tool.name}' maps to undeclared pack '$pack'.", OrionFindingSeverity.CRITICAL, "AgentPolicy:${tool.name}->$pack")
            }
        }

        val skills = runCatching { SkillRegistry.list(app) }.getOrDefault(emptyList())
        val registeredNames = tools.map { it.name }.toSet()
        skills.filter { it.enabled }.forEach { skill ->
            val missing = skill.allowedTools.filterNot { it in registeredNames }
            if (missing.isNotEmpty()) add("skill_registry", "Skill references missing tools", "Enabled skill '${skill.id}' references unregistered tools: ${missing.take(8).joinToString()}.", OrionFindingSeverity.ERROR, "SkillRegistry:${skill.id}")
        }

        val extensions = runCatching { SelfExtendingExtensionStore(app).all() }.getOrDefault(emptyList())
        val verifiedEnabled = extensions.count { it.enabled && it.verified && it.sourceSha256 == SelfExtendingExtensionPolicy.sha256(it.source) }
        extensions.filter { it.enabled && (!it.verified || it.sourceSha256 != SelfExtendingExtensionPolicy.sha256(it.source)) }.forEach {
            add("extension_registry", "Enabled extension failed integrity check", "Extension '${it.id}' is enabled but is not verified against its current source hash.", OrionFindingSeverity.CRITICAL, "Extension:${it.id}")
        }

        val runtime = runCatching { OrionRuntime(app) }
        val runtimeSnapshot = runtime.getOrNull()?.snapshot()
        val pendingWork = runtime.getOrNull()?.pendingItems()?.size ?: 0
        val runtimeState = runtimeSnapshot?.state?.name ?: "UNAVAILABLE"
        if (runtimeSnapshot?.state == OrionRuntime.State.DEGRADED) {
            add("background_runtime", "Background runtime is degraded", "The durable work runtime reports DEGRADED; inspect the last event and pending work before resuming.", OrionFindingSeverity.WARNING, "OrionRuntime:${runtimeSnapshot.lastEvent}")
        }
        if (pendingWork > 0 && runtimeSnapshot?.state == OrionRuntime.State.STOPPED) {
            add("background_runtime", "Background work is waiting for service", "$pendingWork durable work item(s) are pending while the runtime is stopped. Android/HyperOS may defer execution.", OrionFindingSeverity.WARNING, "OrionRuntime:pending=$pendingWork")
        }

        val checkpoint = runCatching { AgentCheckpointStore.pending(app) }.getOrNull()
        if (checkpoint != null) {
            val status = checkpoint.optString("status", "unknown").lowercase()
            val inFlight = checkpoint.optJSONObject("inFlight")?.optString("tool").orEmpty()
            if (status in setOf("recovery_required", "failed", "needs_review") || inFlight.isNotBlank()) {
                add("task_checkpoint", "Task needs reconciliation", "Checkpoint status='$status'; unresolved in-flight tool='${inFlight.ifBlank { "none" }}'. Inspect the actual outcome before resuming.", OrionFindingSeverity.WARNING, "AgentCheckpointStore:status=$status")
            }
        }

        return OrionDiagnosticReport(
            generatedAtEpochMs = now, findings = findings.distinctBy { it.id }.take(40),
            registeredToolCount = tools.size, enabledSkillCount = skills.count { it.enabled },
            enabledVerifiedExtensionCount = verifiedEnabled, runtimeState = runtimeState,
            queuedBackgroundWork = pendingWork
        )
    }
}
