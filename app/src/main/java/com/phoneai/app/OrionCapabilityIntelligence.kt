package com.phoneai.app

import android.content.Context
import android.content.pm.PackageManager
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * OrionCapabilityIntelligence — Autonomous capability discovery, gap analysis, and acquisition planning.
 *
 * Embodies the doctrine: "When access is missing, Orion should investigate legitimate alternatives
 * and expand its capabilities."
 *
 * This is the brain's sensory organ for the capability ecosystem — continuously mapping what's
 * available, what's missing, what's degraded, and formulating acquisition strategies.
 */
class OrionCapabilityIntelligence(
    private val context: Context,
    private val capabilityRegistry: CapabilityRegistry,
    private val toolRegistry: ToolRegistry,
    private val capabilityBroker: OrionCapabilityBroker?,
    private val extensionStore: SelfExtendingExtensionStore,
    private val experienceStore: ExperienceStore,
    private val recall: OrionRecall,
    private val knowledgeGraph: OrionKnowledgeGraph,
    private val entityIndex: OrionEntityIndex,
    private val embeddingProvider: OrionEmbeddingProvider
) {

    private val healthCache = ConcurrentHashMap<String, CapabilityHealth>()
    private val discoveryTimestamp = AtomicLong(0)
    private val acquisitionHistory = ConcurrentHashMap<String, AcquisitionRecord>()

    // Configuration thresholds
    companion object {
        const val HEALTH_CACHE_TTL_MS = 5 * 60 * 1000 // 5 minutes
        const val DISCOVERY_INTERVAL_MS = 30 * 60 * 1000 // 30 minutes
        const val DEGRADED_SUCCESS_RATE_THRESHOLD = 0.7
        const val CRITICAL_SUCCESS_RATE_THRESHOLD = 0.4
    }

    /**
     * Full capability ecosystem discovery — maps the entire terrain of what Orion can do.
     */
    suspend fun discoverFullCapabilityEcosystem(): CapabilityEcosystemMap {
        val timestamp = System.currentTimeMillis()
        discoveryTimestamp.set(timestamp)

        // 1. Registered capabilities from CapabilityRegistry
        val registeredCaps = discoverRegisteredCapabilities()

        // 2. Available tools from ToolRegistry (including extension tools)
        val availableTools = discoverAvailableTools()

        // 3. Extension capabilities from SelfExtendingExtensionStore
        val extensionCaps = discoverExtensionCapabilities()

        // 4. Android system capabilities (permissions, apps, services)
        val systemCaps = discoverSystemCapabilities()

        // 5. External service capabilities (MCP, APIs, etc.)
        val externalCaps = discoverExternalCapabilities()

        // 6. Health assessment for all discovered capabilities
        val healthMap = assessAllHealth(registeredCaps + availableTools + extensionCaps + systemCaps + externalCaps)

        // 7. Gap analysis — what objectives can't be achieved?
        val gaps = analyzeCapabilityGaps(healthMap)

        // 8. Acquisition opportunities — what can be added?
        val opportunities = identifyAcquisitionOpportunities(gaps, healthMap)

        return CapabilityEcosystemMap(
            timestamp = timestamp,
            registeredCapabilities = registeredCaps,
            availableTools = availableTools,
            extensionCapabilities = extensionCaps,
            systemCapabilities = systemCaps,
            externalCapabilities = externalCaps,
            healthAssessments = healthMap,
            gaps = gaps,
            acquisitionOpportunities = opportunities
        )
    }

    /**
     * Rapid capability check for a specific objective — used during mission planning.
     */
    suspend fun assessCapabilityForObjective(objective: String, intentType: OrionIntentIntelligence.IntentType): ObjectiveCapabilityAssessment {
        val ecosystem = if (System.currentTimeMillis() - discoveryTimestamp.get() > DISCOVERY_INTERVAL_MS) {
            discoverFullCapabilityEcosystem()
        } else {
            getCachedEcosystem()
        }

        // Find relevant capabilities for this objective
        val relevantCaps = ecosystem.allCapabilities.filter { cap ->
            isCapabilityRelevant(cap, objective, intentType)
        }

        // Check if we have sufficient capability coverage
        val coverage = calculateCoverage(relevantCaps, objective)
        val missing = identifyMissingCapabilities(relevantCaps, objective)
        val degraded = relevantCaps.filter { healthCache[it.id]?.status == CapabilityStatus.DEGRADED }
        val blocked = relevantCaps.filter { healthCache[it.id]?.status == CapabilityStatus.BLOCKED }

        // Determine if objective is achievable
        val achievable = coverage >= 0.8 && missing.isEmpty() && blocked.isEmpty()

        // Generate acquisition plan for missing/degraded
        val acquisitionPlan = if (!achievable) {
            generateAcquisitionPlan(missing + degraded, objective)
        } else null

        return ObjectiveCapabilityAssessment(
            objective = objective,
            intentType = intentType,
            achievable = achievable,
            coverageScore = coverage,
            availableCapabilities = relevantCaps,
            missingCapabilities = missing,
            degradedCapabilities = degraded,
            blockedCapabilities = blocked,
            acquisitionPlan = acquisitionPlan,
            confidence = calculateConfidence(coverage, missing.size, degraded.size)
        )
    }

    /**
     * Continuous health monitoring — runs in background to keep assessments fresh.
     */
    suspend fun refreshHealthAssessments() {
        val ecosystem = discoverFullCapabilityEcosystem()
        assessAllHealth(ecosystem.allCapabilities)
    }

    /**
     * Record capability usage outcome for learning.
     */
    fun recordCapabilityOutcome(capabilityId: String, success: Boolean, latencyMs: Long, errorType: String?) {
        val health = healthCache.getOrPut(capabilityId) { CapabilityHealth(capabilityId) }
        health.recordOutcome(success, latencyMs, errorType)

        // Update acquisition history if this was a recently acquired capability
        acquisitionHistory[capabilityId]?.let { record ->
            record.recordOutcome(success, latencyMs)
        }
    }

    // ============================================================
    // Discovery Methods
    // ============================================================

    private suspend fun discoverRegisteredCapabilities(): List<DiscoveredCapability> {
        return capabilityRegistry.getAllCapabilities().map { cap ->
            DiscoveredCapability(
                id = cap.id,
                name = cap.name,
                type = CapabilityType.REGISTERED,
                description = cap.description,
                toolIds = cap.tools,
                packId = cap.packId,
                enabled = cap.enabled,
                source = "CapabilityRegistry",
                metadata = cap.metadata
            )
        }
    }

    private suspend fun discoverAvailableTools(): List<DiscoveredCapability> {
        return toolRegistry.getAllTools().map { tool ->
            DiscoveredCapability(
                id = "tool:${tool.id}",
                name = tool.name,
                type = CapabilityType.TOOL,
                description = tool.description,
                toolIds = listOf(tool.id),
                packId = tool.capabilityPackId,
                enabled = tool.enabled && (tool.capabilityPackId?.let { capabilityRegistry.isPackEnabled(it) } ?: true),
                source = "ToolRegistry",
                metadata = JsonObject().apply {
                    addProperty("category", tool.category)
                    addProperty("requiresConfirmation", tool.requiresConfirmation)
                    addProperty("androidPermissions", tool.requiredAndroidPermissions.joinToString(","))
                }
            )
        }
    }

    private suspend fun discoverExtensionCapabilities(): List<DiscoveredCapability> {
        return extensionStore.getAllExtensions().map { ext ->
            DiscoveredCapability(
                id = "ext:${ext.id}",
                name = ext.name,
                type = CapabilityType.EXTENSION,
                description = ext.description,
                toolIds = ext.providedTools,
                packId = "self-extended",
                enabled = ext.enabled && ext.verified,
                source = "SelfExtendingExtensionStore",
                metadata = JsonObject().apply {
                    addProperty("version", ext.version)
                    addProperty("verified", ext.verified)
                    addProperty("source", ext.source.name)
                    addProperty("installPath", ext.installPath)
                }
            )
        }
    }

    private suspend fun discoverSystemCapabilities(): List<DiscoveredCapability> {
        val caps = mutableListOf<DiscoveredCapability>()
        val pm = context.packageManager

        // Installed apps that can be opened/controlled
        val launchableApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { app ->
                DiscoveredCapability(
                    id = "app:${app.packageName}",
                    name = app.loadLabel(pm).toString(),
                    type = CapabilityType.SYSTEM_APP,
                    description = "Android application: ${app.packageName}",
                    toolIds = listOf("open_app"),
                    packId = "system",
                    enabled = true,
                    source = "PackageManager",
                    metadata = JsonObject().apply {
                        addProperty("packageName", app.packageName)
                        addProperty("version", pm.getPackageInfo(app.packageName, 0).versionName)
                    }
                )
            }
        caps.addAll(launchableApps)

        // System permissions we hold
        val grantedPerms = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.filter { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
            .map { perm ->
                DiscoveredCapability(
                    id = "perm:$perm",
                    name = perm,
                    type = CapabilityType.SYSTEM_PERMISSION,
                    description = "Granted Android permission",
                    toolIds = emptyList(),
                    packId = "system",
                    enabled = true,
                    source = "PackageManager",
                    metadata = JsonObject().apply {
                        addProperty("permission", perm)
                    }
                )
            } ?: emptyList()
        caps.addAll(grantedPerms)

        // Accessibility service status
        val accessibilityEnabled = isAccessibilityServiceEnabled()
        caps.add(DiscoveredCapability(
            id = "system:accessibility",
            name = "Accessibility Service",
            type = CapabilityType.SYSTEM_SERVICE,
            description = "UI automation and screen reading",
            toolIds = listOf("click", "type_text", "read_screen", "scroll", "open_app"),
            packId = "system",
            enabled = accessibilityEnabled,
            source = "AccessibilityManager",
            metadata = JsonObject().apply {
                addProperty("enabled", accessibilityEnabled)
            }
        ))

        // Termux availability
        val termuxAvailable = isTermuxAvailable()
        caps.add(DiscoveredCapability(
            id = "system:termux",
            name = "Termux Linux Environment",
            type = CapabilityType.SYSTEM_ENVIRONMENT,
            description = "Linux command execution via Termux",
            toolIds = listOf("termux_command", "shell"),
            packId = "system",
            enabled = termuxAvailable,
            source = "TermuxBridge",
            metadata = JsonObject().apply {
                addProperty("available", termuxAvailable)
            }
        ))

        // Python runtime
        val pythonAvailable = isPythonAvailable()
        caps.add(DiscoveredCapability(
            id = "system:python",
            name = "Python Runtime",
            type = CapabilityType.SYSTEM_ENVIRONMENT,
            description = "Python script execution",
            toolIds = listOf("python_exec", "python_script"),
            packId = "system",
            enabled = pythonAvailable,
            source = "OrionPythonRuntime",
            metadata = JsonObject().apply {
                addProperty("available", pythonAvailable)
            }
        ))

        return caps
    }

    private suspend fun discoverExternalCapabilities(): List<DiscoveredCapability> {
        val caps = mutableListOf<DiscoveredCapability>()

        // MCP servers
        val mcpServers = discoverMCPServers()
        caps.addAll(mcpServers)

        // API integrations (from vault/credentials)
        val apiIntegrations = discoverAPIIntegrations()
        caps.addAll(apiIntegrations)

        return caps
    }

    private suspend fun discoverMCPServers(): List<DiscoveredCapability> {
        // Query MCP manager for connected servers
        return try {
            // This would integrate with actual MCP manager
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun discoverAPIIntegrations(): List<DiscoveredCapability> {
        // Check ApiKeyVault for stored credentials
        return try {
            val vault = ApiKeyVault
            vault.listKeys().map { key ->
                DiscoveredCapability(
                    id = "api:$key",
                    name = "API: $key",
                    type = CapabilityType.EXTERNAL_API,
                    description = "Authenticated external API access",
                    toolIds = listOf("vault_get", "http_request"),
                    packId = "credentials",
                    enabled = true,
                    source = "ApiKeyVault",
                    metadata = JsonObject().apply {
                        addProperty("keyId", key)
                    }
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ============================================================
    // Health Assessment
    // ============================================================

    private fun assessAllHealth(capabilities: List<DiscoveredCapability>): Map<String, CapabilityHealth> {
        val result = mutableMapOf<String, CapabilityHealth>()
        capabilities.forEach { cap ->
            val health = healthCache.getOrPut(cap.id) { CapabilityHealth(cap.id) }
            health.updateFromCapability(cap)
            result[cap.id] = health
        }
        return result
    }

    // ============================================================
    // Gap Analysis
    // ============================================================

    private fun analyzeCapabilityGaps(healthMap: Map<String, CapabilityHealth>): List<CapabilityGap> {
        val gaps = mutableListOf<CapabilityGap>()

        // Query recent failed missions from experience store
        val recentFailures = experienceStore.getRecentFailures(limit = 50)

        // Group failures by missing capability patterns
        val failurePatterns = recentFailures
            .flatMap { it.missingCapabilities ?: emptyList() }
            .groupBy { it }
            .mapValues { (cap, occurrences) -> occurrences.size }

        failurePatterns.forEach { (capabilityId, count) ->
            if (count >= 2) { // Pattern threshold
                val health = healthMap[capabilityId]
                gaps.add(CapabilityGap(
                    capabilityId = capabilityId,
                    gapType = when {
                        health == null -> GapType.MISSING
                        health.status == CapabilityStatus.BLOCKED -> GapType.BLOCKED
                        health.status == CapabilityStatus.DEGRADED -> GapType.DEGRADED
                        else -> GapType.INSUFFICIENT
                    },
                    frequency = count,
                    impact = calculateGapImpact(capabilityId, count),
                    suggestedAcquisition = suggestAcquisitionForGap(capabilityId, health)
                ))
            }
        }

        // Also check for objective patterns that consistently fail
        val objectiveFailures = recentFailures
            .groupBy { it.objectiveType }
            .mapValues { (_, failures) -> failures.size }
            .filter { (_, count) -> count >= 3 }

        objectiveFailures.forEach { (objectiveType, count) ->
            gaps.add(CapabilityGap(
                capabilityId = "objective:$objectiveType",
                gapType = GapType.OBJECTIVE_PATTERN,
                frequency = count,
                impact = GapImpact.HIGH,
                suggestedAcquisition = AcquisitionSuggestion(
                    type = AcquisitionType.COMPOSITE_WORKFLOW,
                    description = "Create specialist workflow for $objectiveType",
                    estimatedEffort = EffortLevel.MEDIUM
                )
            ))
        }

        return gaps.sortedByDescending { it.frequency * it.impact.weight }
    }

    private fun calculateGapImpact(capabilityId: String, frequency: Int): GapImpact {
        return when {
            frequency >= 10 -> GapImpact.CRITICAL
            frequency >= 5 -> GapImpact.HIGH
            frequency >= 2 -> GapImpact.MEDIUM
            else -> GapImpact.LOW
        }
    }

    private fun suggestAcquisitionForGap(capabilityId: String, health: CapabilityHealth?): AcquisitionSuggestion {
        return when {
            health == null -> {
                // Completely missing — check if extension exists
                val existingExt = extensionStore.getAllExtensions().find { ext ->
                    capabilityId in ext.providedTools || ext.id == capabilityId
                }
                if (existingExt != null) {
                    AcquisitionSuggestion(
                        type = AcquisitionType.ENABLE_EXTENSION,
                        description = "Enable existing extension: ${existingExt.name}",
                        estimatedEffort = EffortLevel.LOW,
                        targetId = existingExt.id
                    )
                } else {
                    AcquisitionSuggestion(
                        type = AcquisitionType.CREATE_EXTENSION,
                        description = "Create new self-extending extension for $capabilityId",
                        estimatedEffort = EffortLevel.HIGH
                    )
                }
            }
            health.status == CapabilityStatus.BLOCKED -> {
                AcquisitionSuggestion(
                    type = AcquisitionType.RESOLVE_BLOCKER,
                    description = "Resolve blocker: ${health.blockReason}",
                    estimatedEffort = EffortLevel.MEDIUM
                )
            }
            health.status == CapabilityStatus.DEGRADED -> {
                AcquisitionSuggestion(
                    type = AcquisitionType.IMPROVE_RELIABILITY,
                    description = "Improve reliability of $capabilityId (success rate: ${health.successRate})",
                    estimatedEffort = EffortLevel.MEDIUM
                )
            }
            else -> {
                AcquisitionSuggestion(
                    type = AcquisitionType.ALTERNATIVE_STRATEGY,
                    description = "Find alternative approach for $capabilityId",
                    estimatedEffort = EffortLevel.LOW
                )
            }
        }
    }

    // ============================================================
    // Acquisition Opportunities
    // ============================================================

    private fun identifyAcquisitionOpportunities(
        gaps: List<CapabilityGap>,
        healthMap: Map<String, CapabilityHealth>
    ): List<AcquisitionOpportunity> {
        val opportunities = mutableListOf<AcquisitionOpportunity>()

        // High-impact gaps become opportunities
        gaps.filter { it.impact.weight >= GapImpact.HIGH.weight }
            .forEach { gap ->
                opportunities.add(AcquisitionOpportunity(
                    gap = gap,
                    priority = when (gap.impact) {
                        GapImpact.CRITICAL -> Priority.IMMEDIATE
                        GapImpact.HIGH -> Priority.HIGH
                        GapImpact.MEDIUM -> Priority.NORMAL
                        else -> Priority.LOW
                    },
                    feasibility = assessFeasibility(gap.suggestedAcquisition),
                    estimatedValue = estimateValue(gap)
                ))
            }

        // Check for popular capabilities we don't have (from knowledge graph)
        val popularCaps = knowledgeGraph.getPopularCapabilities(limit = 20)
        popularCaps.forEach { cap ->
            if (!healthMap.containsKey(cap.id) && !gaps.any { it.capabilityId == cap.id }) {
                opportunities.add(AcquisitionOpportunity(
                    gap = CapabilityGap(
                        capabilityId = cap.id,
                        gapType = GapType.MISSING,
                        frequency = cap.popularity,
                        impact = GapImpact.MEDIUM,
                        suggestedAcquisition = AcquisitionSuggestion(
                            type = AcquisitionType.CREATE_EXTENSION,
                            description = "Popular capability: ${cap.name}",
                            estimatedEffort = EffortLevel.HIGH
                        )
                    ),
                    priority = Priority.NORMAL,
                    feasibility = FeasibilityLevel.MODERATE,
                    estimatedValue = cap.popularity * 0.1
                ))
            }
        }

        return opportunities.sortedByDescending { it.priority.weight * it.feasibility.weight * it.estimatedValue }
    }

    private fun assessFeasibility(suggestion: AcquisitionSuggestion): FeasibilityLevel {
        return when (suggestion.type) {
            AcquisitionType.ENABLE_EXTENSION -> FeasibilityLevel.HIGH
            AcquisitionType.RESOLVE_BLOCKER -> FeasibilityLevel.MODERATE
            AcquisitionType.IMPROVE_RELIABILITY -> FeasibilityLevel.MODERATE
            AcquisitionType.ALTERNATIVE_STRATEGY -> FeasibilityLevel.HIGH
            AcquisitionType.CREATE_EXTENSION -> FeasibilityLevel.LOW
            AcquisitionType.COMPOSITE_WORKFLOW -> FeasibilityLevel.MODERATE
            AcquisitionType.INSTALL_PACKAGE -> FeasibilityLevel.HIGH
            AcquisitionType.CONFIGURE_INTEGRATION -> FeasibilityLevel.MODERATE
        }
    }

    private fun estimateValue(gap: CapabilityGap): Double {
        return gap.frequency * gap.impact.weight * 0.5
    }

    // ============================================================
    // Objective Assessment Helpers
    // ============================================================

    private fun getCachedEcosystem(): CapabilityEcosystemMap {
        // Return a minimal cached version — in practice would cache the full map
        return CapabilityEcosystemMap(
            timestamp = discoveryTimestamp.get(),
            registeredCapabilities = capabilityRegistry.getAllCapabilities().map { it.toDiscovered() },
            availableTools = toolRegistry.getAllTools().map { it.toDiscovered() },
            extensionCapabilities = extensionStore.getAllExtensions().map { it.toDiscovered() },
            systemCapabilities = emptyList(),
            externalCapabilities = emptyList(),
            healthAssessments = healthCache.toMap(),
            gaps = emptyList(),
            acquisitionOpportunities = emptyList()
        )
    }

    private fun isCapabilityRelevant(cap: DiscoveredCapability, objective: String, intentType: OrionIntentIntelligence.IntentType): Boolean {
        // Simple keyword matching — in practice would use embeddings
        val objectiveLower = objective.lowercase()
        val capText = "${cap.name} ${cap.description} ${cap.toolIds.joinToString(" ")}".lowercase()

        // Direct keyword overlap
        val keywords = objectiveLower.split(" ").filter { it.length > 2 }
        val directMatch = keywords.any { capText.contains(it) }

        // Intent-type relevance
        val intentMatch = when (intentType) {
            OrionIntentIntelligence.IntentType.CODE -> cap.type in setOf(CapabilityType.TOOL, CapabilityType.EXTENSION, CapabilityType.SYSTEM_ENVIRONMENT)
            OrionIntentIntelligence.IntentType.RESEARCH -> cap.type in setOf(CapabilityType.TOOL, CapabilityType.EXTERNAL_API, CapabilityType.SYSTEM_APP)
            OrionIntentIntelligence.IntentType.COMMUNICATION -> cap.type in setOf(CapabilityType.TOOL, CapabilityType.SYSTEM_APP, CapabilityType.EXTERNAL_API)
            OrionIntentIntelligence.IntentType.SYSTEM_CONTROL -> cap.type in setOf(CapabilityType.SYSTEM_SERVICE, CapabilityType.SYSTEM_PERMISSION, CapabilityType.TOOL)
            else -> true
        }

        return directMatch || intentMatch
    }

    private fun calculateCoverage(caps: List<DiscoveredCapability>, objective: String): Double {
        if (caps.isEmpty()) return 0.0
        val healthy = caps.count { healthCache[it.id]?.status != CapabilityStatus.BLOCKED }
        return healthy.toDouble() / caps.size
    }

    private fun identifyMissingCapabilities(caps: List<DiscoveredCapability>, objective: String): List<DiscoveredCapability> {
        // In practice, would use embeddings to find semantic gaps
        return caps.filter { healthCache[it.id]?.status == CapabilityStatus.MISSING }
    }

    private fun calculateConfidence(coverage: Double, missingCount: Int, degradedCount: Int): Double {
        var confidence = coverage
        confidence -= missingCount * 0.2
        confidence -= degradedCount * 0.1
        return confidence.coerceIn(0.0, 1.0)
    }

    private fun generateAcquisitionPlan(missing: List<DiscoveredCapability>, objective: String): AcquisitionPlan {
        val steps = missing.map { cap ->
            val suggestion = suggestAcquisitionForGap(cap.id, healthCache[cap.id])
            AcquisitionStep(
                capabilityId = cap.id,
                action = suggestion.type,
                description = suggestion.description,
                estimatedEffort = suggestion.estimatedEffort,
                prerequisites = emptyList()
            )
        }
        return AcquisitionPlan(
            objective = objective,
            steps = steps,
            totalEstimatedEffort = steps.sumOf { it.estimatedEffort.weight },
            parallelizable = steps.size > 1
        )
    }

    // ============================================================
    // System Checks
    // ============================================================

    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        return accessibilityManager.enabledAccessibilityServiceList?.any {
            it.id.contains("phoneai") || it.id.contains("orion")
        } ?: false
    }

    private fun isTermuxAvailable(): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.termux", 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun isPythonAvailable(): Boolean {
        return try {
            // Check if Python runtime is installed via OrionPythonRuntime
            OrionPythonRuntime.isPythonAvailable(context)
        } catch (e: Exception) {
            false
        }
    }
}

// ============================================================
// Data Classes
// ============================================================

data class CapabilityEcosystemMap(
    val timestamp: Long,
    val registeredCapabilities: List<DiscoveredCapability>,
    val availableTools: List<DiscoveredCapability>,
    val extensionCapabilities: List<DiscoveredCapability>,
    val systemCapabilities: List<DiscoveredCapability>,
    val externalCapabilities: List<DiscoveredCapability>,
    val healthAssessments: Map<String, CapabilityHealth>,
    val gaps: List<CapabilityGap>,
    val acquisitionOpportunities: List<AcquisitionOpportunity>
) {
    val allCapabilities: List<DiscoveredCapability>
        get() = registeredCapabilities + availableTools + extensionCapabilities + systemCapabilities + externalCapabilities
}

data class DiscoveredCapability(
    val id: String,
    val name: String,
    val type: CapabilityType,
    val description: String,
    val toolIds: List<String>,
    val packId: String,
    val enabled: Boolean,
    val source: String,
    val metadata: JsonObject
)

enum class CapabilityType {
    REGISTERED, TOOL, EXTENSION, SYSTEM_APP, SYSTEM_PERMISSION, SYSTEM_SERVICE, SYSTEM_ENVIRONMENT, EXTERNAL_API
}

data class CapabilityHealth(
    val capabilityId: String,
    var status: CapabilityStatus = CapabilityStatus.UNKNOWN,
    var successRate: Double = 1.0,
    var avgLatencyMs: Long = 0,
    var totalCalls: Long = 0,
    var successfulCalls: Long = 0,
    var lastError: String? = null,
    var lastErrorTime: Long = 0,
    var blockReason: String? = null,
    var lastAssessed: Long = System.currentTimeMillis()
) {
    fun recordOutcome(success: Boolean, latencyMs: Long, errorType: String?) {
        totalCalls++
        if (success) {
            successfulCalls++
            lastError = null
        } else {
            lastError = errorType
            lastErrorTime = System.currentTimeMillis()
        }
        successRate = successfulCalls.toDouble() / totalCalls
        avgLatencyMs = (avgLatencyMs * (totalCalls - 1) + latencyMs) / totalCalls
        updateStatus()
    }

    fun updateFromCapability(cap: DiscoveredCapability) {
        if (!cap.enabled) {
            status = CapabilityStatus.BLOCKED
            blockReason = "Disabled in registry"
        } else if (status == CapabilityStatus.UNKNOWN) {
            status = CapabilityStatus.HEALTHY
        }
        lastAssessed = System.currentTimeMillis()
    }

    private fun updateStatus() {
        when {
            successRate >= OrionCapabilityIntelligence.DEGRADED_SUCCESS_RATE_THRESHOLD -> status = CapabilityStatus.HEALTHY
            successRate >= OrionCapabilityIntelligence.CRITICAL_SUCCESS_RATE_THRESHOLD -> status = CapabilityStatus.DEGRADED
            else -> status = CapabilityStatus.CRITICAL
        }
    }
}

enum class CapabilityStatus {
    UNKNOWN, HEALTHY, DEGRADED, CRITICAL, BLOCKED, MISSING
}

data class ObjectiveCapabilityAssessment(
    val objective: String,
    val intentType: OrionIntentIntelligence.IntentType,
    val achievable: Boolean,
    val coverageScore: Double,
    val availableCapabilities: List<DiscoveredCapability>,
    val missingCapabilities: List<DiscoveredCapability>,
    val degradedCapabilities: List<DiscoveredCapability>,
    val blockedCapabilities: List<DiscoveredCapability>,
    val acquisitionPlan: AcquisitionPlan?,
    val confidence: Double
)

data class CapabilityGap(
    val capabilityId: String,
    val gapType: GapType,
    val frequency: Int,
    val impact: GapImpact,
    val suggestedAcquisition: AcquisitionSuggestion
)

enum class GapType {
    MISSING, BLOCKED, DEGRADED, INSUFFICIENT, OBJECTIVE_PATTERN
}

enum class GapImpact(val weight: Int) {
    LOW(1), MEDIUM(2), HIGH(3), CRITICAL(4)
}

data class AcquisitionSuggestion(
    val type: AcquisitionType,
    val description: String,
    val estimatedEffort: EffortLevel,
    val targetId: String? = null
)

enum class AcquisitionType {
    ENABLE_EXTENSION, RESOLVE_BLOCKER, IMPROVE_RELIABILITY, ALTERNATIVE_STRATEGY,
    CREATE_EXTENSION, COMPOSITE_WORKFLOW, INSTALL_PACKAGE, CONFIGURE_INTEGRATION
}

enum class EffortLevel(val weight: Int) {
    LOW(1), MEDIUM(2), HIGH(3), VERY_HIGH(4)
}

data class AcquisitionOpportunity(
    val gap: CapabilityGap,
    val priority: Priority,
    val feasibility: FeasibilityLevel,
    val estimatedValue: Double
)

enum class Priority(val weight: Int) {
    LOW(1), NORMAL(2), HIGH(3), IMMEDIATE(4)
}

enum class FeasibilityLevel(val weight: Int) {
    LOW(1), MODERATE(2), HIGH(3)
}

data class AcquisitionPlan(
    val objective: String,
    val steps: List<AcquisitionStep>,
    val totalEstimatedEffort: Int,
    val parallelizable: Boolean
)

data class AcquisitionStep(
    val capabilityId: String,
    val action: AcquisitionType,
    val description: String,
    val estimatedEffort: EffortLevel,
    val prerequisites: List<String>
)

data class AcquisitionRecord(
    val capabilityId: String,
    val acquisitionType: AcquisitionType,
    val timestamp: Long,
    var successCount: Long = 0,
    var failureCount: Long = 0,
    var lastUsed: Long = 0
) {
    fun recordOutcome(success: Boolean, latencyMs: Long) {
        if (success) successCount++ else failureCount++
        lastUsed = System.currentTimeMillis()
    }
    val successRate: Double get() = if (successCount + failureCount > 0) successCount.toDouble() / (successCount + failureCount) else 0.0
}

// Extension functions for conversion
private fun Capability.toDiscovered(): DiscoveredCapability = DiscoveredCapability(
    id = id, name = name, type = CapabilityType.REGISTERED, description = description,
    toolIds = tools, packId = packId, enabled = enabled, source = "CapabilityRegistry",
    metadata = JsonObject().apply { addProperty("metadata", metadata.toString()) }
)

private fun Tool.toDiscovered(): DiscoveredCapability = DiscoveredCapability(
    id = "tool:$id", name = name, type = CapabilityType.TOOL, description = description,
    toolIds = listOf(id), packId = capabilityPackId ?: "core", enabled = enabled,
    source = "ToolRegistry", metadata = JsonObject()
)

private fun SelfExtendingExtension.toDiscovered(): DiscoveredCapability = DiscoveredCapability(
    id = "ext:$id", name = name, type = CapabilityType.EXTENSION, description = description,
    toolIds = providedTools, packId = "self-extended", enabled = enabled && verified,
    source = "SelfExtendingExtensionStore", metadata = JsonObject()
)