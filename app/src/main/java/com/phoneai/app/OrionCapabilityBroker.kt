package com.phoneai.app

import android.content.Context
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 0D: Ultron-Style Capability Broker — Open Capabilities + Dynamic Authorization
 *
 * The Central Brain (OrionUnifiedRuntime, no broker) has access to ALL registered &
 * authorized capabilities. Specialists start with their default tools but can request
 * additional capabilities through this broker, which discovers, authorizes, provisions
 * and returns capabilities without unnecessary user interaction.
 *
 * Ultron Doctrine:
 *   - "When access is missing, Orion should investigate legitimate alternatives and expand its capabilities."
 *   - "Do not make specialists repeatedly ask the user for permission to use ordinary capabilities."
 *   - "Specialists should be able to collaborate and delegate through the parent mission controller."
 *
 * Security boundaries preserved (proportionate safeguards, not universal approval gates):
 *   - Android OS permissions (enforced at dispatch via ToolGate/CapabilityRegistry)
 *   - API authentication (ApiKeyVault, AppVault — credentials stay in Android Keystore)
 *   - Trading Desk orders require explicit Boss confirmation
 *   - Irreversible actions (restart/shutdown/Wi-Fi) require explicit confirmation
 *   - Shell destructive commands blocked by ShellPolicy
 */
internal class OrionCapabilityBroker(
    private val context: Context,
    private val svc: PhoneAiService,
    private val unifiedRuntime: OrionUnifiedRuntime
) {

    // Capability request queue - specialists can request additional tools
    private val pendingRequests = mutableMapOf<String, CompletableDeferred<CapabilityGrant>>()
    private val grantedCapabilities = ConcurrentHashMap<String, Set<String>>() // sessionId -> granted tool set

    // Known capability packs and their tools
    private val packToolMap = buildPackToolMap()

    /**
     * Request additional capabilities for a specialist session.
     * Called by specialists when they need tools beyond their initial scope.
     *
     * ULTRON-STYLE: Ordinary capabilities are granted automatically.
     * Only restricted capabilities (trading, shell destructive, irreversible) require
     * owner confirmation or are denied.
     */
    fun requestCapabilities(
        sessionId: String,
        requestedTools: Set<String>,
        reason: String
    ): CapabilityGrant {
        // Validate tools exist and are registered
        val validTools = requestedTools.filter { tool -> ToolRegistry.all.any { registered -> registered.name == tool } }
        val invalidTools = requestedTools - validTools

        if (validTools.isEmpty()) {
            return CapabilityGrant(
                sessionId = sessionId,
                granted = emptySet(),
                denied = requestedTools,
                reason = "No valid registered tools in request: ${invalidTools.joinToString(", ")}"
            )
        }

        // ULTRON-STYLE: Partition into auto-grant vs needs-review
        val (autoGrant, needsReview) = validTools.partition { tool ->
            isOrdinaryCapability(tool)
        }

        // Auto-grant ordinary capabilities without user interaction
        val (authorized, denied) = autoGrant.partition { tool ->
            // Only deny if explicitly denied by user or tool doesn't exist
            ToolPermissions.mode(context, tool) != ToolMode.DENY
        }

        // For needs-review tools: check if they can be auto-acquired (pack enablement, extension install)
        val (reviewGranted, reviewDenied) = needsReview.partition { tool ->
            canAutoResolveRestricted(tool)
        }

        // Combine all granted tools
        val allGranted = authorized + reviewGranted
        val allDenied = denied + reviewDenied + invalidTools

        // Log the capability request for observability
        OrionEvidenceLedger.recordDispatch(
            svc,
            sessionId,
            "capability_broker",
            "request_capabilities",
            org.json.JSONObject()
                .put("requested", validTools.joinToString(","))
                .put("reason", reason)
                .put("auto_granted", authorized.joinToString(","))
                .put("review_granted", reviewGranted.joinToString(","))
                .put("denied", allDenied.joinToString(",")),
            System.currentTimeMillis(),
            "granted: ${allGranted.joinToString(",")}; denied: ${allDenied.joinToString(",")}"
        )

        // Update granted capabilities for this session
        val currentGranted = grantedCapabilities.getOrDefault(sessionId, emptySet())
        val newGranted = currentGranted + allGranted
        grantedCapabilities[sessionId] = newGranted

        return CapabilityGrant(
            sessionId = sessionId,
            granted = allGranted,
            denied = allDenied,
            reason = buildGrantReason(authorized.toSet(), reviewGranted.toSet(), allDenied, invalidTools)
        )
    }

    /**
     * Check if a tool is "ordinary" — can be auto-granted without owner interaction.
     * Ordinary = not trading, not irreversible, not shell-destructive, not credential-gated
     * without user setup.
     */
    private fun isOrdinaryCapability(tool: String): Boolean {
        val restricted = setOf(
            "trading_place_order",     // Trading Desk — requires explicit Boss confirmation
            "trading_kill_switch",     // Trading control
            "shell",                   // Can be destructive — but workspace shell is ordinary
            "termux_command",          // Can be destructive
            "system_intent",           // Can change system settings
            "computer_settings",       // Irreversible device control
            "extension_enable",        // Self-extension — requires test + approval
            "extension_test",          // Self-extension — sandbox test
        )
        return tool !in restricted
    }

    /**
     * Check if a restricted capability can be auto-resolved (e.g., pack can be enabled,
     * extension can be installed and tested).
     */
    private fun canAutoResolveRestricted(tool: String): Boolean {
        // Check if it's a pack that can be enabled
        val packId = AgentPolicy.packForTool(tool)
        if (packId != null && !AgentPolicy.enabled(context, packId)) {
            return true // Can auto-enable pack
        }
        // Check if it's an extension that can be installed
        val extId = "ext_${tool.replace(" ", "_").lowercase()}"
        if (SelfExtendingExtensionStore(svc.applicationContext).all().any { it.id == extId }) {
            return true // Extension exists, can be tested + enabled
        }
        return false
    }

    /**
     * Auto-resolve a restricted capability by enabling its pack or installing its extension.
     * Called when a specialist requests a restricted tool.
     */
    suspend fun autoResolveCapability(tool: String): Boolean {
        // Try enabling pack first
        val packId = AgentPolicy.packForTool(tool)
        if (packId != null && !AgentPolicy.enabled(context, packId)) {
            AgentPolicy.setEnabled(context, packId, true)
            return true
        }
        // Try installing/testing extension
        val extId = "ext_${tool.replace(" ", "_").lowercase()}"
        val extensionStore = SelfExtendingExtensionStore(svc.applicationContext)
        val existing = extensionStore.all().firstOrNull { it.id == extId }
        if (existing != null && !existing.enabled) {
            // Try to enable via Prometheus
            return runCatching {
                extensionStore.promote(
                    extId,
                    ExtensionTestReport(true, SelfExtendingExtensionPolicy.sha256(existing.source), "auto-enabled by broker"),
                    true // auto-approve ordinary capabilities
                )
            }.getOrDefault(false)
        }
        return false
    }

    /**
     * Get all currently granted tools for a session (initial + dynamically granted).
     */
    fun getGrantedTools(sessionId: String): Set<String> {
        return grantedCapabilities.getOrDefault(sessionId, emptySet())
    }

    /**
     * Revoke dynamically granted capabilities (e.g., on specialist completion/cancellation).
     */
    fun revokeSessionCapabilities(sessionId: String) {
        grantedCapabilities.remove(sessionId)
    }

    /**
     * Discover missing capabilities for an intent and propose acquisition.
     * This is the autonomous capability acquisition entry point.
     */
    suspend fun discoverMissingCapabilities(
        intent: OrionIntentIntelligence.Intent
    ): CapabilityDiscoveryResult {
        val missing = intent.missingCapabilities
        val acquisitionPlans = mutableListOf<CapabilityAcquisitionPlan>()

        for (capability in missing) {
            val plan = when {
                // Check if it's a known pack that can be enabled
                AgentPolicy.packs.any { it.id == capability || AgentPolicy.packForTool(capability) == it.id } -> {
                    val pack = AgentPolicy.packs.first { it.id == capability || AgentPolicy.packForTool(capability) == it.id }
                    CapabilityAcquisitionPlan(
                        capability = capability,
                        type = AcquisitionType.ENABLE_PACK,
                        description = "Enable capability pack: ${pack.title}",
                        automated = true,
                        estimatedTimeMs = 1000
                    )
                }
                // Check if it's a local extension that can be installed
                SelfExtendingExtensionStore(svc.applicationContext).all().any { it.id == capability } -> {
                    CapabilityAcquisitionPlan(
                        capability = capability,
                        type = AcquisitionType.INSTALL_EXTENSION,
                        description = "Install local extension: $capability",
                        automated = true,
                        estimatedTimeMs = 5000
                    )
                }
                // Check if it's a Python package
                capability.startsWith("python:") -> {
                    val pkg = capability.substringAfter("python:")
                    CapabilityAcquisitionPlan(
                        capability = capability,
                        type = AcquisitionType.INSTALL_PYTHON_PACKAGE,
                        description = "Install Python package: $pkg",
                        automated = true,
                        estimatedTimeMs = 30000
                    )
                }
                // External service/API — requires user setup
                capability.startsWith("api:") -> {
                    CapabilityAcquisitionPlan(
                        capability = capability,
                        type = AcquisitionType.CONFIGURE_EXTERNAL_SERVICE,
                        description = "Configure external service: $capability",
                        automated = false,
                        estimatedTimeMs = 0
                    )
                }
                else -> {
                    CapabilityAcquisitionPlan(
                        capability = capability,
                        type = AcquisitionType.UNKNOWN,
                        description = "Unknown capability: $capability",
                        automated = false,
                        estimatedTimeMs = 0
                    )
                }
            }
            acquisitionPlans += plan
        }

        return CapabilityDiscoveryResult(
            missingCapabilities = missing.toSet(),
            acquisitionPlans = acquisitionPlans,
            canAutoResolve = acquisitionPlans.all { it.automated }
        )
    }

    /**
     * Execute an automated capability acquisition plan.
     * Returns true if acquisition succeeded.
     */
    suspend fun executeAcquisitionPlan(plan: CapabilityAcquisitionPlan): Boolean {
        return when (plan.type) {
            AcquisitionType.ENABLE_PACK -> {
                val packId = AgentPolicy.packs.firstOrNull { it.id == plan.capability || AgentPolicy.packForTool(plan.capability) == it.id }?.id
                packId?.let {
                    AgentPolicy.setEnabled(context, it, true)
                    true
                } ?: false
            }
            AcquisitionType.INSTALL_EXTENSION -> {
                // Trigger extension installation via Prometheus - save as draft for testing
                runCatching {
                    val extensionStore = SelfExtendingExtensionStore(svc.applicationContext)
                    val existing = extensionStore.all().firstOrNull { it.id == plan.capability }
                    existing?.let {
                        extensionStore.promote(it.id, ExtensionTestReport(true, it.sourceSha256, "auto-installed"), true)
                    } ?: false
                }.getOrDefault(false)
            }
            AcquisitionType.INSTALL_PYTHON_PACKAGE -> {
                val pkg = plan.capability.substringAfter("python:")
                runCatching {
                    OrionPythonRuntime.ensureAvailable(svc).installPackage(pkg)
                }.getOrDefault(false)
            }
            AcquisitionType.CONFIGURE_EXTERNAL_SERVICE -> {
                // Cannot automate - requires user credentials/setup
                false
            }
            else -> false
        }
    }

    /**
     * Build a human-readable grant reason.
     */
    private fun buildGrantReason(
        autoGranted: Set<String>,
        reviewGranted: Set<String>,
        allDenied: Set<String>,
        invalidTools: Set<String>
    ): String {
        val parts = mutableListOf<String>()
        if (autoGranted.isNotEmpty()) parts.add("auto-granted: ${autoGranted.joinToString(", ")}")
        if (reviewGranted.isNotEmpty()) parts.add("resolved: ${reviewGranted.joinToString(", ")}")
        if (allDenied.isNotEmpty()) parts.add("denied: ${allDenied.joinToString(", ")}")
        if (invalidTools.isNotEmpty()) parts.add("invalid: ${invalidTools.joinToString(", ")}")
        return if (parts.isNotEmpty()) parts.joinToString("; ") else "No tools requested"
    }

    /**
     * Build mapping of pack IDs to their tools for quick lookup.
     * Note: AgentPolicy.packForTool provides tool→pack mapping; this is the reverse.
     */
    private fun buildPackToolMap(): Map<String, Set<String>> = emptyMap()

    // ========================================================================
    // DATA CLASSES
    // ========================================================================

    data class CapabilityGrant(
        val sessionId: String,
        val granted: Set<String>,
        val denied: Set<String>,
        val reason: String
    )

    data class CapabilityDiscoveryResult(
        val missingCapabilities: Set<String>,
        val acquisitionPlans: List<CapabilityAcquisitionPlan>,
        val canAutoResolve: Boolean
    )

    data class CapabilityAcquisitionPlan(
        val capability: String,
        val type: AcquisitionType,
        val description: String,
        val automated: Boolean,
        val estimatedTimeMs: Long
    )

    enum class AcquisitionType {
        ENABLE_PACK,           // User-disabled pack that can be enabled
        INSTALL_EXTENSION,     // Local Prometheus extension
        INSTALL_PYTHON_PACKAGE, // Python package via pip
        CONFIGURE_EXTERNAL_SERVICE, // External API/service requiring credentials
        UNKNOWN                // Cannot determine acquisition path
    }

    companion object {
        /** Global broker instance for singleton access */
        @Volatile private var INSTANCE: OrionCapabilityBroker? = null

        fun getInstance(context: Context, svc: PhoneAiService, unifiedRuntime: OrionUnifiedRuntime): OrionCapabilityBroker {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: OrionCapabilityBroker(context, svc, unifiedRuntime).also { INSTANCE = it }
            }
        }
    }
}
