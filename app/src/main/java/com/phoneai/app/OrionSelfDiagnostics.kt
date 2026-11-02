package com.phoneai.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Local, provider-free health inspection for the central brain. */
internal data class OrionDiagnosticReport(
    val generatedAtEpochMs: Long,
    val findings: List<OrionDiagnosticFinding>,
    val registeredToolCount: Int,
    val enabledSkillCount: Int,
    val enabledVerifiedExtensionCount: Int,
    val runtimeState: String,
    val queuedBackgroundWork: Int?
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
    fun isExplicitRequest(request: String?): Boolean = !request.isNullOrBlank() && Regex(
        "(?i)\\b(self[- ]?(?:diagnos(?:e|is|tic|ing)?|heal(?:ing)?)|system health check|system audit|check your own (?:health|tools|capabilities)|what is broken in you)\\b"
    ).containsMatchIn(request)

    fun inspect(context: Context, now: Long = System.currentTimeMillis(), recordActivity: Boolean = false): OrionDiagnosticReport {
        val app = context.applicationContext
        val findings = mutableListOf<OrionDiagnosticFinding>()
        fun add(component: String, title: String, detail: String, severity: OrionFindingSeverity, evidence: String) {
            val safeComponent = component.take(60)
            val safeEvidence = evidence.take(180)
            val id = "diag-" + safeComponent.lowercase().replace(Regex("[^a-z0-9]+"), "-") + "-" + detail.hashCode().toString()
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

        val pythonWorkspace = runCatching { OrionPythonWorkspace(app).rootStatus() }.getOrNull()
        when (pythonWorkspace) {
            OrionPythonWorkspace.RootStatus.MISSING -> add(
                "python_workspace", "Python workspace root is missing",
                "The fixed app-private workspace directory has not been created; bounded Python file capabilities may need repair.",
                OrionFindingSeverity.WARNING, "OrionPythonWorkspace:root_missing"
            )
            OrionPythonWorkspace.RootStatus.UNSAFE -> add(
                "python_workspace", "Python workspace path is unsafe",
                "A workspace ancestor is a symlink or the root is not a readable/writable directory; automatic repair is blocked.",
                OrionFindingSeverity.CRITICAL, "OrionPythonWorkspace:root_unsafe"
            )
            OrionPythonWorkspace.RootStatus.READY, null -> Unit
        }

        // Report the real Android half of each active consent grant. An Orion scope toggle
        // is not an Android permission and must never be presented as proof of OS access.
        fun androidPermissionGranted(permission: String): Boolean =
            ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

        if (OrionSensorConsent.read(app, OrionSensorScope.LOCATION).active() &&
            !androidPermissionGranted(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !androidPermissionGranted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            add("android_permissions", "Location grant lacks Android permission",
                "Orion location consent is active, but Android has not granted fine or coarse location access.",
                OrionFindingSeverity.WARNING, "Android:ACCESS_FINE_LOCATION|ACCESS_COARSE_LOCATION")
        }
        if (OrionSensorConsent.read(app, OrionSensorScope.CONTACTS).active() &&
            !androidPermissionGranted(Manifest.permission.READ_CONTACTS)) {
            add("android_permissions", "Contacts grant lacks Android permission",
                "Orion contacts consent is active, but Android READ_CONTACTS permission is not granted.",
                OrionFindingSeverity.WARNING, "Android:READ_CONTACTS")
        }
        if (OrionSensorConsent.read(app, OrionSensorScope.MICROPHONE).active() &&
            !androidPermissionGranted(Manifest.permission.RECORD_AUDIO)) {
            add("android_permissions", "Microphone grant lacks Android permission",
                "Orion microphone consent is active, but Android RECORD_AUDIO permission is not granted.",
                OrionFindingSeverity.WARNING, "Android:RECORD_AUDIO")
        }
        if (OrionSensorConsent.read(app, OrionSensorScope.SCREEN_CONTEXT).active()) {
            val component = ComponentName(app, PhoneAiService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(
                app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty().split(':').any { it.equals(component, ignoreCase = true) }
            if (!enabled || PhoneAiService.instance == null) {
                add("android_accessibility", "Accessibility bridge is not connected",
                    "Screen-context consent is active, but Orion's Android accessibility service is not both enabled and connected.",
                    OrionFindingSeverity.WARNING, "Android:ENABLED_ACCESSIBILITY_SERVICES|PhoneAiService.instance")
            }
        }
        if (OrionSensorConsent.read(app, OrionSensorScope.NOTIFICATIONS).active()) {
            val component = ComponentName(app, OrionNotificationListener::class.java).flattenToString()
            val enabled = Settings.Secure.getString(
                app.contentResolver, "enabled_notification_listeners"
            ).orEmpty().split(':').any { it.equals(component, ignoreCase = true) }
            if (!enabled) {
                add("android_notifications", "Notification listener is not enabled",
                    "Orion notification consent is active, but Android Notification access is not enabled for Orion.",
                    OrionFindingSeverity.WARNING, "Android:enabled_notification_listeners")
            }
        }

        val runtimeState = runCatching { PhoneAiService.instance?.runtimeBriefForBrain() }.getOrNull()
            ?.take(300).orEmpty().ifBlank { "SERVICE_DISCONNECTED_OR_UNAVAILABLE" }
        // Inspect the real durable specialist queue; registration counts alone do not show
        // whether background work is waiting, paused or needs owner reconciliation.
        val workerJobs = runCatching { OrionWorkerJobStore(app).all() }.getOrNull()
        val pendingStatuses = setOf(
            OrionWorkerJobStore.Status.QUEUED,
            OrionWorkerJobStore.Status.RUNNING,
            OrionWorkerJobStore.Status.RESULT_PENDING,
            OrionWorkerJobStore.Status.PAUSED,
            OrionWorkerJobStore.Status.NEEDS_REVIEW
        )
        val pendingWork: Int? = workerJobs?.count { it.status in pendingStatuses }
        if (workerJobs == null) {
            add("specialist_queue", "Specialist queue state unavailable",
                "The durable specialist queue could not be read; pending work count is unknown, not zero.",
                OrionFindingSeverity.WARNING, "OrionWorkerJobStore:read_failed")
        }
        workerJobs.orEmpty().filter { it.status == OrionWorkerJobStore.Status.NEEDS_REVIEW }.take(8).forEach { job ->
            add("specialist_queue", "Specialist result needs review",
                "A delegated ${job.kind.name.lowercase()} job needs parent-brain reconciliation; its result is not an instruction or proof of completion.",
                OrionFindingSeverity.WARNING, "WorkerJob:${job.id.take(12)}")
        }
        if (runtimeState.contains("DEGRADED", ignoreCase = true)) {
            add("background_runtime", "Background runtime is degraded", "The connected service reports a degraded runtime; inspect its persisted queue and last event before resuming.", OrionFindingSeverity.WARNING, "PhoneAiService.runtimeBriefForBrain")
        }

        val checkpoint = runCatching { AgentCheckpointStore.pending(app) }.getOrNull()
        if (checkpoint != null) {
            val status = checkpoint.optString("status", "unknown").lowercase()
            val inFlight = checkpoint.optJSONObject("inFlight")?.optString("tool").orEmpty()
            if (status in setOf("recovery_required", "failed", "needs_review") || inFlight.isNotBlank()) {
                add("task_checkpoint", "Task needs reconciliation", "Checkpoint status='$status'; unresolved in-flight tool='${inFlight.ifBlank { "none" }}'. Inspect the actual outcome before resuming.", OrionFindingSeverity.WARNING, "AgentCheckpointStore:status=$status")
            }
        }

        val report = OrionDiagnosticReport(
            generatedAtEpochMs = now, findings = findings.distinctBy { it.id }.take(40),
            registeredToolCount = tools.size, enabledSkillCount = skills.count { it.enabled },
            enabledVerifiedExtensionCount = verifiedEnabled, runtimeState = runtimeState,
            queuedBackgroundWork = pendingWork
        )
        if (recordActivity) runCatching {
            val errors = report.findings.count { it.severity == OrionFindingSeverity.ERROR || it.severity == OrionFindingSeverity.CRITICAL }
            val warnings = report.findings.count { it.severity == OrionFindingSeverity.WARNING }
            val status = if (errors > 0) "issues_found" else "completed"
            val summary = "Local self-diagnostic completed: tools=${report.registeredToolCount}, enabled skills=${report.enabledSkillCount}, verified extensions=${report.enabledVerifiedExtensionCount}, structural errors=$errors, warnings=$warnings. Remote provider health, Android permission grants and physical-device reliability were not tested."
            OrionConversationRepository.get(app).recordEvent(app, null, "self_diagnostic", status, summary.take(700))
        }
        return report
    }
}
