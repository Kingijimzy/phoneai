package com.phoneai.app

import android.content.Context
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 0C: Dynamic Capability Broker
 *
 * Central capability intelligence that enables dynamic tool expansion for specialists.
 * The Central Brain (OrionUnifiedRuntime) has access to all registered/authorized capabilities.
 * Specialists start with their default tools but can request additional capabilities through
 * this broker, which discovers, authorizes, provisions, and returns capabilities without
 * unnecessary user interaction.
 *
 * Security boundaries preserved:
 * - Android OS permissions (checked at dispatch time via ToolGate)
 * - API authentication (ApiKeyVault, AppVault)
 * - Trading Desk orders require explicit Boss confirmation
 * - Irreversible actions (restart/shutdown/Wi-Fi) require explicit confirmation
 * - Shell destructive commands blocked by ShellPolicy
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
     * @param sessionId The specialist session ID
     * @param requestedTools Set of tool names requested
     * @param reason Why these tools are needed (for audit/logging)
     * @return CapabilityGrant with the newly authorized tools
     */
    suspend fun requestCapabilities(
        sessionId: String,
        requestedTools: Set<String>,
        reason: String
    ): CapabilityGrant {
        // Validate tools exist and are registered
        val validTools = requestedTools.filter { ToolRegistry.all.any { it.name == it } }
        val invalidTools = requestedTools - validTools

        if (validTools.isEmpty()) {
            return CapabilityGrant(
                sessionId = sessionId,
                granted = emptySet(),
                denied = requestedTools,
                reason = "No valid registered tools in request: ${invalidTools.joinToString(", ")}"
            )
        }

        // Check each tool for authorization (pack status, permissions, health)
        val (authorized, denied) = validTools.partition { tool ->
            isToolAuthorizedForSpecialist(tool)
        }

        // Log the capability request for observability
        OrionEvidenceLedger.recordDispatch(
            svc,
            sessionId,
            "capability_broker",
            "request_capabilities",
            org.json.JSONObject().put("requested", validTools.joinToString(",")).put("reason", reason),
            System.currentTimeMillis(),
            "granted: ${authorized.joinToString(",")}; denied: ${denied.joinToString(",")}"
        )

        // Update granted capabilities for this session
        val currentGranted = grantedCapabilities.getOrDefault(sessionId, emptySet())
        val newGranted = currentGranted + authorized
        grantedCapabilities[sessionId] = newGranted

        return CapabilityGrant(
            sessionId = sessionId,
            granted = authorized,
            denied = denied + invalidTools,
            reason = if (denied.isNotEmpty()) "Denied: ${denied.joinToString(", ")} (pack off, permission missing, or health failed)" else "All requested tools granted"
        )
    }

    /**
     * Check if a tool is authorized for specialist use.
     * Considers: pack enabled, not denied by user, capability health, Android permissions.
     */
    private fun isToolAuthorizedForSpecialist(tool: String): Boolean {
        // Core system tools always allowed
        if (tool in ToolPermissionPolicy.alwaysAllowed) return true

        // Check user deny setting
        if (ToolPermissions.mode(context, tool) == ToolMode.DENY) return false

        // Check pack status
        val pack = AgentPolicy.packForTool(tool)
        if (pack != null && !AgentPolicy.enabled(context, pack)) return false

        // Check capability registry health
        val capabilityEntry = CapabilityRegistry.forBuiltInTool(
            tool,
            ToolPermissions.mode(context, tool) == ToolMode.DENY,
            pack == null || AgentPolicy.enabled(context, pack)
        )
        if (!capabilityEntry.usable) return false

        // ToolGate preflight (permissions, etc.)
        if (ToolGate.preflightDenial(context, tool) != null) return false

        return true
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
                AgentPolicy.packs.any { it.id == capability || it.tools.any { it == capability } } -> {
                    val pack = AgentPolicy.packs.first { it.id == capability || it.tools.any { it == capability } }
                    CapabilityAcquisitionPlan(
                        capability = capability,
                        type = AcquisitionType.ENABLE_PACK,
                        description = "Enable capability pack: ${pack.title}",
                        automated = true,
                        estimatedTimeMs = 1000
                    )
                }
                // Check if it's a local extension that can be installed
                OrionExtensionStore.isExtensionAvailable(capability) -> {
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
                // External service/API
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
            missingCapabilities = missing,
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
                val packId = AgentPolicy.packs.firstOrNull { it.id == plan.capability || it.tools.any { it == plan.capability } }?.id
                packId?.let {
                    AgentPolicy.setEnabled(context, it, true)
                    true
                } ?: false
            }
            AcquisitionType.INSTALL_EXTENSION -> {
                // Trigger extension installation via Prometheus
                runCatching {
                    SelfExtendingExtensionStore.installAndTest(svc, plan.capability)
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
     * Build mapping of pack IDs to their tools for quick lookup.
     */
    private fun buildPackToolMap(): Map<String, Set<String>> {
        return AgentPolicy.packs.associate { it.id to it.tools.toSet() }
    }

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