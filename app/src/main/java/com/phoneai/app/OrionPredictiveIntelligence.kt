package com.phoneai.app
import com.phoneai.app.OrionContextIntegration.DeviceContext

import org.json.JSONArray
import org.json.JSONObject

/**
 * Orion Predictive Intelligence
 *
 * Identifies likely obstacles and prepares alternative strategies.
 * Uses pattern recognition from mission history, device state, and capability availability.
 */
class OrionPredictiveIntelligence(
    private val svc: PhoneAiService,
    private val recall: OrionRecall,
    private val knowledgeGraph: OrionKnowledgeGraph,
    private val capabilityDiscovery: OrionCapabilityDiscovery,
    private val proactiveEngine: OrionProactiveEngine,
    private val chronos: OrionChronosAwareness
) {

    data class Prediction(
        val predictionId: String,
        val timestamp: Long,
        val type: PredictionType,
        val description: String,
        val probability: Double, // 0.0 - 1.0
        val impact: Impact,
        val timeframe: Timeframe,
        val indicators: List<String>,
        val recommendedActions: List<RecommendedAction>,
        val confidence: Double
    ) {
        enum class PredictionType {
            CAPABILITY_GAP,       // Missing tool/skill will be needed
            RESOURCE_EXHAUSTION,  // Battery, quota, memory will run low
            PERMISSION_DENIED,    // Android permission or user approval will block
            NETWORK_FAILURE,      // Network-dependent tool will fail
            EXECUTION_TIMEOUT,    // Step will exceed timeout
            VERIFICATION_FAILURE, // Action will not verify
            DEPENDENCY_BLOCK,     // Dependent task will not complete
            SKILL_OBSOLETE,       // Registered skill no longer works
            MACRO_STALE,          // Saved macro steps no longer valid
            DEVICE_STATE_CHANGE,  // Screen off, app backgrounded, etc.
            USER_UNAVAILABLE,     // User not available for confirmation
            EXTERNAL_API_ERROR    // Trading, weather, news API will fail
        }

        enum class Impact { NEGLIGIBLE, MINOR, MODERATE, MAJOR, CATASTROPHIC }

        enum class Timeframe { IMMEDIATE, SHORT_TERM, MEDIUM_TERM, LONG_TERM }
    }

    data class RecommendedAction(
        val action: String,
        val description: String,
        val priority: Priority,
        val estimatedEffort: Effort
    ) {
        enum class Priority { CRITICAL, HIGH, MEDIUM, LOW }
        enum class Effort { TRIVIAL, LOW, MEDIUM, HIGH }
    }

    data class ObstacleReport(
        val predictions: List<Prediction>,
        val overallRiskScore: Double, // 0.0 - 1.0
        val criticalPredictions: List<Prediction>,
        val mitigationPlan: MitigationPlan
    )

    data class MitigationPlan(
        val preemptiveActions: List<String>,
        val contingencyStrategies: Map<String, String>, // predictionId -> strategy
        val monitoringRules: List<MonitoringRule>
    )

    data class MonitoringRule(
        val ruleId: String,
        val condition: String, // Human-readable condition
        val checkIntervalMs: Long,
        val actionOnTrigger: String
    )

    /**
     * Predict obstacles for a given plan and context.
     */
    fun predictObstacles(
        plan: OrionPlanningEngine.Plan,
        intent: OrionIntentIntelligence.Intent,
        currentContext: PredictiveContext
    ): ObstacleReport {
        val predictions = mutableListOf<Prediction>()

        // 1. Capability gap predictions
        predictions.addAll(predictCapabilityGaps(plan, intent, currentContext))

        // 2. Resource exhaustion predictions
        predictions.addAll(predictResourceExhaustion(plan, currentContext))

        // 3. Permission denied predictions
        predictions.addAll(predictPermissionIssues(plan, intent, currentContext))

        // 4. Network failure predictions
        predictions.addAll(predictNetworkFailures(plan, currentContext))

        // 5. Execution timeout predictions
        predictions.addAll(predictTimeouts(plan, currentContext))

        // 6. Verification failure predictions
        predictions.addAll(predictVerificationFailures(plan, intent, currentContext))

        // 7. Dependency block predictions
        predictions.addAll(predictDependencyBlocks(plan, currentContext))

        // 8. Skill/macro staleness predictions
        predictions.addAll(predictStaleness(plan, currentContext))

        // 8. Device state change predictions
        predictions.addAll(predictDeviceStateChanges(currentContext))

        // 9. User availability predictions
        predictions.addAll(predictUserAvailability(currentContext))

        // 10. External API error predictions
        predictions.addAll(predictExternalApiErrors(plan, currentContext))

        // Filter by probability threshold
        val relevantPredictions = predictions.filter { it.probability > 0.15 }

        // Sort by risk (probability * impact)
        val sorted = relevantPredictions.sortedByDescending { it.probability * impactWeight(it.impact) }

        // Identify critical predictions
        val critical = sorted.filter { it.probability > 0.5 && it.impact in setOf(Prediction.Impact.MAJOR, Prediction.Impact.CATASTROPHIC) }

        // Calculate overall risk score
        val overallRisk = calculateOverallRisk(sorted)

        // Build mitigation plan
        val mitigationPlan = buildMitigationPlan(sorted, intent)

        return ObstacleReport(
            predictions = sorted,
            overallRiskScore = overallRisk,
            criticalPredictions = critical,
            mitigationPlan = mitigationPlan
        )
    }

    private fun predictCapabilityGaps(
        plan: OrionPlanningEngine.Plan,
        intent: OrionIntentIntelligence.Intent,
        context: PredictiveContext
    ): List<Prediction> {
        val predictions = mutableListOf<Prediction>()
        val availableCaps = capabilityDiscovery.getAvailableCapabilities(svc).map { it.name }.toSet()

        for (node in plan.nodes) {
            if (node.capability !in availableCaps) {
                val probability = if (node.isOptional) 0.3 else 0.9
                predictions.add(Prediction(
                    predictionId = "capgap_${node.id}",
                    timestamp = System.currentTimeMillis(),
                    type = Prediction.PredictionType.CAPABILITY_GAP,
                    description = "Required capability '${node.capability}' not available for step '${node.objective}'",
                    probability = probability,
                    impact = if (node.isOptional) Prediction.Impact.MINOR else Prediction.Impact.MAJOR,
                    timeframe = Prediction.Timeframe.IMMEDIATE,
                    indicators = listOf("Capability not in registry", "ToolGate.preflightDenial returns BLOCKED"),
                    recommendedActions = listOf(
                        RecommendedAction("acquire_capability", "Use Prometheus to acquire missing capability", RecommendedAction.Priority.HIGH, RecommendedAction.Effort.MEDIUM),
                        RecommendedAction("use_alternative", "Select alternative tool from registered capabilities", RecommendedAction.Priority.HIGH, RecommendedAction.Effort.LOW),
                        RecommendedAction("defer_step", "Defer this step until capability available", RecommendedAction.Priority.MEDIUM, RecommendedAction.Effort.TRIVIAL)
                    ),
                    confidence = 0.95
                ))
            }
        }

        // Check for capabilities that might be needed based on intent but not in plan
        for (cap in intent.suggestedCapabilities) {
            if (cap !in availableCaps && plan.nodes.none { it.capability == cap }) {
                predictions.add(Prediction(
                    predictionId = "capgap_implicit_$cap",
                    timestamp = System.currentTimeMillis(),
                    type = Prediction.PredictionType.CAPABILITY_GAP,
                    description = "Implicitly needed capability '$cap' not available",
                    probability = 0.4,
                    impact = Prediction.Impact.MODERATE,
                    timeframe = Prediction.Timeframe.SHORT_TERM,
                    indicators = listOf("Intent analysis suggests $cap", "Not in current plan"),
                    recommendedActions = listOf(
                        RecommendedAction("monitor_need", "Monitor if capability becomes needed during execution", RecommendedAction.Priority.MEDIUM, RecommendedAction.Effort.TRIVIAL)
                    ),
                    confidence = 0.7
                ))
            }
        }

        return predictions
    }

    private fun predictResourceExhaustion(
        plan: OrionPlanningEngine.Plan,
        context: PredictiveContext
    ): List<Prediction> {
        val predictions = mutableListOf<Prediction>()

        // Battery prediction
        context.deviceContext?.batteryLevel?.let { battery ->
            val estimatedDrain = estimateBatteryDrain(plan)
            if (battery - estimatedDrain < 15) {
                predictions.add(Prediction(
                    predictionId = "resource_battery",
                    timestamp = System.currentTimeMillis(),
                    type = Prediction.PredictionType.RESOURCE_EXHAUSTION,
                    description = "Battery will drop below 15% during execution (current: $battery%, estimated drain: $estimatedDrain%)",
                    probability = 0.8,
                    impact = Prediction.Impact.MAJOR,
                    timeframe = Prediction.Timeframe.MEDIUM_TERM,
                    indicators = listOf("Battery: $battery%", "Estimated drain: $estimatedDrain%", "Long-running plan: ${plan.estimatedDurationMs / 1000}s"),
                    recommendedActions = listOf(
                        RecommendedAction("charge_device", "Connect charger before starting", RecommendedAction.Priority.CRITICAL, RecommendedAction.Effort.TRIVIAL),
                        RecommendedAction("reduce_scope", "Defer non-critical steps", RecommendedAction.Priority.HIGH, RecommendedAction.Effort.LOW),
                        RecommendedAction("enable_power_save", "Enable battery saver mode", RecommendedAction.Priority.MEDIUM, RecommendedAction.Effort.TRIVIAL)
                    ),
                    confidence = 0.85
                ))
            }
        }

        // API quota prediction
        val autonomySettings = OrionAutonomySettings.read(svc)
        val estimatedRequests = plan.nodes.count { it.tool != "verify_action" && it.tool != "verify_send" }
        if (estimatedRequests > autonomySettings.maxAiRequests * 0.8) {
            predictions.add(Prediction(
                predictionId = "resource_api_quota",
                timestamp = System.currentTimeMillis(),
                type = Prediction.PredictionType.RESOURCE_EXHAUSTION,
                description = "Plan may exceed API quota (estimated $estimatedRequests requests, limit ${autonomySettings.maxAiRequests})",
                probability = 0.7,
                impact = Prediction.Impact.MODERATE,
                timeframe = Prediction.Timeframe.MEDIUM_TERM,
                indicators = listOf("Estimated requests: $estimatedRequests", "Quota: ${autonomySettings.maxAiRequests}"),
                recommendedActions = listOf(
                    RecommendedAction("batch_requests", "Batch multiple tool calls where possible", RecommendedAction.Priority.HIGH, RecommendedAction.Effort.LOW),
                    RecommendedAction("use_local_tools", "Prefer local/device tools over AI calls", RecommendedAction.Priority.HIGH, RecommendedAction.Effort.LOW),
                    RecommendedAction("increase_quota", "Adjust autonomy settings if appropriate", RecommendedAction.Priority.MEDIUM, RecommendedAction.Effort.TRIVIAL)
                ),
                confidence = 0.8
            ))
        }

        // Memory/storage prediction
        val storage = context.availableStorageMb ?: 0
        if (storage < 100) {
            predictions.add(Prediction(
                predictionId = "resource_storage",
                timestamp = System.currentTimeMillis(),
                type = Prediction.PredictionType.RESOURCE_EXHAUSTION,
                description = "Low storage space (${storage}MB) may cause file operations to fail",
                probability = 0.6,
                impact = Prediction.Impact.MODERATE,
                timeframe = Prediction.Timeframe.IMMEDIATE,
                indicators = listOf("Available storage: ${storage}MB"),
                recommendedActions = listOf(
                    RecommendedAction("clear_cache", "Clear app cache and temporary files", RecommendedAction.Priority.HIGH, RecommendedAction.Effort.LOW),
                    RecommendedAction("use_streaming", "Use streaming for large files", RecommendedAction.Priority.MEDIUM, RecommendedAction.Effort.LOW)
                ),
                confidence = 0.7
            ))
        }

        return predictions
    }

    private fun predictPermissionIssues(
        plan: OrionPlanningEngine.Plan,
        intent: OrionIntentIntelligence.Intent,
        context: PredictiveContext
    ): List<Prediction> {
        val predictions = mutableListOf<Prediction>()

        // Trading permission
        if (plan.nodes.any { it.tool == "trading_place_order" }) {
            predictions.add(Prediction(
                predictionId = "perm_trading",
                timestamp = System.currentTimeMillis(),
                type = Prediction.PredictionType.PERMISSION_DENIED,
                description = "Trading order requires explicit user confirmation",
                probability = 1.0, // Always requires confirmation
                impact = Prediction.Impact.MAJOR,
                timeframe = Prediction.Timeframe.IMMEDIATE,
                indicators = listOf("Trading tool in plan", "IrreversibleActionConfirmationPolicy requires confirmation"),
                recommendedActions = listOf(
                    RecommendedAction("request_confirmation", "Prompt user for explicit approval before trading step", RecommendedAction.Priority.CRITICAL, RecommendedAction.Effort.TRIVIAL)
                ),
                confidence = 1.0
            ))
        }

        // System control permissions
        val systemTools = setOf("computer_settings_restart", "computer_settings_shutdown", "computer_settings_wifi_toggle")
        if (plan.nodes.any { it.tool in systemTools }) {
            predictions.add(Prediction(
                predictionId = "perm_system",
                timestamp = System.currentTimeMillis(),
                type = Prediction.PredictionType.PERMISSION_DENIED,
                description = "System control action requires user confirmation",
                probability = 1.0,
                impact = Prediction.Impact.MAJOR,
                timeframe = Prediction.Timeframe.IMMEDIATE,
                indicators = listOf("System tool in plan: ${plan.nodes.find { it.tool in systemTools }?.tool}"),
                recommendedActions = listOf(
                    RecommendedAction("request_confirmation", "Prompt user for explicit approval", RecommendedAction.Priority.CRITICAL, RecommendedAction.Effort.TRIVIAL)
                ),
                confidence = 1.0
            ))
        }

        // Android permissions
        val androidPermTools = mapOf(
            "click" to "android.permission.ACCESSIBILITY",
            "open_app" to "android.permission.ACCESSIBILITY",
            "read_screen" to "android.permission.ACCESSIBILITY",
            "browser_navigate" to "android.permission.INTERNET",
            "web_search" to "android.permission.INTERNET"
        )

        for (node in plan.nodes) {
            androidPermTools[node.tool]?.let { perm ->
                val granted = androidx.core.content.ContextCompat.checkSelfPermission(svc, perm) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    predictions.add(Prediction(
                        predictionId = "perm_android_${node.tool}",
                        timestamp = System.currentTimeMillis(),
                        type = Prediction.PredictionType.PERMISSION_DENIED,
                        description = "Tool '${node.tool}' requires Android permission '$perm' which is not granted",
                        probability = 0.9,
                        impact = Prediction.Impact.HIGH,
                        timeframe = Prediction.Timeframe.IMMEDIATE,
                        indicators = listOf("Permission $perm not granted", "Tool ${node.tool} needs it"),
                        recommendedActions = listOf(
                            RecommendedAction("request_permission", "Prompt user to grant $perm in settings", RecommendedAction.Priority.HIGH, RecommendedAction.Effort.LOW),
                            RecommendedAction("use_alternative", "Use tool that doesn't require this permission", RecommendedAction.Priority.MEDIUM, RecommendedAction.Effort.LOW)
                        ),
                        confidence = 0.85
                    ))
                }
            }
        }

        return predictions
    }

    private fun predictNetworkFailures(
        plan: OrionPlanningEngine.Plan,
        context: PredictiveContext
    ): List<Prediction> {
        val predictions = mutableListOf<Prediction>()
        val networkTools = setOf("web_search", "browser_navigate", "fetch_url", "browser_click", "browser_type", "browser_snapshot", "trading_place_order", "trading_portfolio", "trading_ticker")

        val networkDependentSteps = plan.nodes.filter { it.tool in networkTools }
        if (networkDependentSteps.isNotEmpty()) {
            val networkType = context.deviceContext?.networkType ?: "unknown"
            val isMetered = context.deviceContext?.isNetworkMetered ?: false
            val signalStrength = context.deviceContext?.signalStrength ?: 0

            var probability = 0.2
            if (networkType == "none" || networkType == "unknown") probability = 0.9
            else if (signalStrength > 0 && signalStrength < 2) probability = 0.6
            else if (isMetered) probability = 0.4

            predictions.add(Prediction(
                predictionId = "network_failure",
                timestamp = System.currentTimeMillis(),
                type = Prediction.PredictionType.NETWORK_FAILURE,
                description = "${networkDependentSteps.size} network-dependent steps may fail (network: $networkType, signal: $signalStrength, metered: $isMetered)",
                probability = probability,
                impact = Prediction.Impact.MAJOR,
                timeframe = Prediction.Timeframe.IMMEDIATE,
                indicators = listOf("Network: $networkType", "Signal: $signalStrength", "Metered: $isMetered", "Network steps: ${networkDependentSteps.size}"),
                recommendedActions = listOf(
                    RecommendedAction("check_connectivity", "Verify network before starting network steps", RecommendedAction.Priority.HIGH, RecommendedAction.Effort.TRIVIAL),
                    RecommendedAction("use_offline_fallback", "Prepare offline alternatives (cached data, local tools)", RecommendedAction.Priority.MEDIUM, RecommendedAction.Effort.LOW),
                    RecommendedAction("retry_with_backoff", "Implement exponential backoff for network calls", RecommendedAction.Priority.MEDIUM, RecommendedAction.Effort.TRIVIAL)
                ),
                confidence = 0.8
            ))
        }

        return predictions
    }

    private fun predictTimeouts(
        plan: OrionPlanningEngine.Plan,
        context: PredictiveContext
    ): List<Prediction> {
        val predictions = mutableListOf<Prediction>()

        for (node in plan.nodes) {
            // Predict based on historical performance
            val historicalAvg = recall.getAverageExecutionTime(node.tool)
            if (historicalAvg > 0 && historicalAvg > node.timeoutMs * 0.8) {
                predictions.add(Prediction(
                    predictionId = "timeout_${node.id}",
                    timestamp = System.currentTimeMillis(),
                    type = Prediction.PredictionType.EXECUTION_TIMEOUT,
                    description = "Step '${node.objective}' (${node.tool}) historically takes ${historicalAvg}ms, timeout is ${node.timeoutMs}ms",
                    probability = 0.7,
                    impact = Prediction.Impact.MODERATE,
                    timeframe = Prediction.Timeframe.SHORT_TERM,
                    indicators = listOf("Historical avg: ${historicalAvg}ms", "Timeout: ${node.timeoutMs}ms"),
                    recommendedActions = listOf(
                        RecommendedAction("increase_timeout", "Increase timeout for this step to ${(historicalAvg * 1.5).toLong()}ms", RecommendedAction.Priority.HIGH, RecommendedAction.Effort.TRIVIAL),
                        RecommendedAction("optimize_step", "Simplify or break down this step", RecommendedAction.Priority.MEDIUM, RecommendedAction.Effort.LOW)
                    ),
                    confidence = 0.8
                ))
            }

            // Browser steps are notoriously slow
            if (node.tool.startsWith("browser_") && node.timeoutMs < 45000) {
                predictions.add(Prediction(
                    predictionId = "timeout_browser_${node.id}",
                    timestamp = System.currentTimeMillis(),
                    type = Prediction.PredictionType.EXECUTION_TIMEOUT,
                    description = "Browser step '${node.objective}' may exceed ${node.timeoutMs}ms timeout",
                    probability = 0.6,
                    impact = Prediction.Impact.MODERATE,
                    timeframe = Prediction.Timeframe.SHORT_TERM,
                    indicators = listOf("Browser automation is slow", "Current timeout: ${node.timeoutMs}ms"),
                    recommendedActions = listOf(
                        RecommendedAction("increase_timeout", "Set browser step timeout to at least 60000ms", RecommendedAction.Priority.HIGH, RecommendedAction.Effort.TRIVIAL),
                        RecommendedAction("use_fetch_url", "Consider fetch_url for simple page retrieval", RecommendedAction.Priority.MEDIUM, RecommendedAction.Effort.LOW)
                    ),
                    confidence = 0.75
                ))
            }
        }

        return predictions
    }

    private fun predictVerificationFailures(
        plan: OrionPlanningEngine.Plan,
        intent: OrionIntentIntelligence.Intent,
        context: PredictiveContext
    ): List<Prediction> {
        val predictions = mutableListOf<Prediction>()

        for (node in plan.nodes) {
            // Screen change verification is unreliable for non-UI actions
            if (node.verificationMethod == OrionIntentIntelligence.SuccessCriterion.VerificationMethod.SCREEN_CHANGE &&
                node.tool !in setOf("open_app", "click", "type_text", "browser_navigate", "browser_click", "browser_type")) {
                predictions.add(Prediction(
                    predictionId = "verify_screen_${node.id}",
                    timestamp = System.currentTimeMillis(),
                    type = Prediction.PredictionType.VERIFICATION_FAILURE,
                    description = "Screen change verification used for non-UI tool '${node.tool}' - likely to fail",
                    probability = 0.8,
                    impact = Prediction.Impact.MODERATE,
                    timeframe = Prediction.Timeframe.IMMEDIATE,
                    indicators = listOf("Tool: ${node.tool}", "Verification: SCREEN_CHANGE"),
                    recommendedActions = listOf(
                        RecommendedAction("change_verification", "Use TOOL_OUTPUT or VERIFY_ACTION instead", RecommendedAction.Priority.HIGH, RecommendedAction.Effort.TRIVIAL)
                    ),
                    confidence = 0.9
                ))
            }

            // Verify_send requires messaging app visible
            if (node.verificationMethod == OrionIntentIntelligence.SuccessCriterion.VerificationMethod.MESSAGE_SENT &&
                node.tool != "send_message") {
                predictions.add(Prediction(
                    predictionId = "verify_send_${node.id}",
                    timestamp = System.currentTimeMillis(),
                    type = Prediction.PredictionType.VERIFICATION_FAILURE,
                    description = "verify_send used for non-messaging tool '${node.tool}' - will fail",
                    probability = 0.95,
                    impact = Prediction.Impact.MAJOR,
                    timeframe = Prediction.Timeframe.IMMEDIATE,
                    indicators = listOf("Tool: ${node.tool}", "Verification: MESSAGE_SENT"),
                    recommendedActions = listOf(
                        RecommendedAction("change_verification", "Use appropriate verification method for ${node.tool}", RecommendedAction.Priority.CRITICAL, RecommendedAction.Effort.TRIVIAL)
                    ),
                    confidence = 0.99
                ))
            }

            // Low verification confidence from historical data
            val historicalVerifyRate = recall.getVerificationSuccessRate(node.tool)
            if (historicalVerifyRate > 0 && historicalVerifyRate < 0.6) {
                predictions.add(Prediction(
                    predictionId = "verify_lowrate_${node.id}",
                    timestamp = System.currentTimeMillis(),
                    type = Prediction.PredictionType.VERIFICATION_FAILURE,
                    description = "Tool '${node.tool}' has low historical verification success rate (${String.format("%.0f%%", historicalVerifyRate * 100)})",
                    probability = 1.0 - historicalVerifyRate,
                    impact = Prediction.Impact.MODERATE,
                    timeframe = Prediction.Timeframe.SHORT_TERM,
                    indicators = listOf("Historical verification rate: ${String.format("%.0f%%", historicalVerifyRate * 100)}"),
                    recommendedActions = listOf(
                        RecommendedAction("add_redundant_verification", "Add secondary verification step", RecommendedAction.Priority.HIGH, RecommendedAction.Effort.LOW),
                        RecommendedAction("improve_tool_usage", "Review tool usage patterns for better verification", RecommendedAction.Priority.MEDIUM, RecommendedAction.Effort.MEDIUM)
                    ),
                    confidence = 0.8
                ))
            }
        }

        return predictions
    }

    private fun predictDependencyBlocks(
        plan: OrionPlanningEngine.Plan,
        context: PredictiveContext
    ): List<Prediction> {
        val predictions = mutableListOf<Prediction>()

        // Check for circular dependencies
        val hasCycles = detectCycles(plan.nodes, plan.edges)
        if (hasCycles) {
            predictions.add(Prediction(
                predictionId = "dep_cycle",
                timestamp = System.currentTimeMillis(),
                type = Prediction.PredictionType.DEPENDENCY_BLOCK,
                description = "Plan contains circular dependencies - execution will deadlock",
                probability = 1.0,
                impact = Prediction.Impact.CATASTROPHIC,
                timeframe = Prediction.Timeframe.IMMEDIATE,
                indicators = listOf("Cycle detected in dependency graph"),
                recommendedActions = listOf(
                    RecommendedAction("fix_dependencies", "Remove circular dependencies before execution", RecommendedAction.Priority.CRITICAL, RecommendedAction.Effort.HIGH)
                ),
                confidence = 1.0
            ))
        }

        // Check for blocked nodes (failed dependencies)
        val failedDeps = plan.nodes.filter { node ->
            node.dependencies.any { dep ->
                plan.nodes.find { it.id == dep }?.status == OrionPlanningEngine.PlanNode.NodeStatus.FAILED
            }
        }
        if (failedDeps.isNotEmpty()) {
            predictions.add(Prediction(
                predictionId = "dep_blocked",
                timestamp = System.currentTimeMillis(),
                type = Prediction.PredictionType.DEPENDENCY_BLOCK,
                description = "${failedDeps.size} steps blocked by failed dependencies",
                probability = 1.0,
                impact = Prediction.Impact.MAJOR,
                timeframe = Prediction.Timeframe.IMMEDIATE,
                indicators = failedDeps.map { "${it.id} blocked by ${it.dependencies.filter { plan.nodes.find { n -> n.id == it }?.status == OrionPlanningEngine.PlanNode.NodeStatus.FAILED }.joinToString(", ")}" },
                recommendedActions = listOf(
                    RecommendedAction("replan", "Replan from failed nodes using alternatives", RecommendedAction.Priority.CRITICAL, RecommendedAction.Effort.HIGH),
                    RecommendedAction("retry_failed", "Retry failed dependency steps with alternatives", RecommendedAction.Priority.HIGH, RecommendedAction.Effort.MEDIUM)
                ),
                confidence = 1.0
            ))
        }

        // External dependencies (waiting for user, external API, etc.)
        val externalWaitSteps = plan.nodes.filter { node ->
            node.tool in setOf("ask_user", "verify_action", "verify_send") || node.isOptional
        }
        if (externalWaitSteps.isNotEmpty()) {
            predictions.add(Prediction(
                predictionId = "dep_external",
                timestamp = System.currentTimeMillis(),
                type = Prediction.PredictionType.DEPENDENCY_BLOCK,
                description = "${externalWaitSteps.size} steps depend on external factors (user input, verification)",
                probability = 0.5,
                impact = Prediction.Impact.MODERATE,
                timeframe = Prediction.Timeframe.SHORT_TERM,
                indicators = externalWaitSteps.map { "${it.id} (${it.tool})" },
                recommendedActions = listOf(
                    RecommendedAction("set_timeouts", "Set reasonable timeouts for external dependencies", RecommendedAction.Priority.HIGH, RecommendedAction.Effort.TRIVIAL),
                    RecommendedAction("provide_defaults", "Provide sensible defaults for optional steps", RecommendedAction.Priority.MEDIUM, RecommendedAction.Effort.LOW)
                ),
                confidence = 0.7
            ))
        }

        return predictions
    }

    private fun predictStaleness(
        plan: OrionPlanningEngine.Plan,
        context: PredictiveContext
    ): List<Prediction> {
        val predictions = mutableListOf<Prediction>()

        // Skill staleness
        for (node in plan.nodes) {
            if (node.tool == "skill_invoke") {
                val skillId = node.argsTemplate.optString("skill_id")
                val skill = OrionSkillsCapabilityExecutor.discover(svc, "", 100).find { it.id == skillId }
                if (skill != null) {
                    val lastUpdated = skill.lastUpdated ?: 0
                    val daysSinceUpdate = (System.currentTimeMillis() - lastUpdated) / (1000 * 60 * 60 * 24)
                    if (daysSinceUpdate > 30) {
                        predictions.add(Prediction(
                            predictionId = "stale_skill_$skillId",
                            timestamp = System.currentTimeMillis(),
                            type = Prediction.PredictionType.SKILL_OBSOLETE,
                            description = "Skill '$skillId' not updated for $daysSinceUpdate days - may be incompatible",
                            probability = minOf(0.3 + daysSinceUpdate * 0.01, 0.8),
                            impact = Prediction.Impact.MODERATE,
                            timeframe = Prediction.Timeframe.MEDIUM_TERM,
                            indicators = listOf("Skill: $skillId", "Last updated: $daysSinceUpdate days ago"),
                            recommendedActions = listOf(
                                RecommendedAction("test_skill", "Test skill before execution", RecommendedAction.Priority.MEDIUM, RecommendedAction.Effort.LOW),
                                RecommendedAction("update_skill", "Update or recreate skill", RecommendedAction.Priority.LOW, RecommendedAction.Effort.MEDIUM)
                            ),
                            confidence = 0.7
                        ))
                    }
                }
            }

            // Macro staleness
            if (node.tool == "macro_run") {
                val macroId = node.argsTemplate.optString("macro_id")
                val macros = OrionMacroExecutor.listMacros(svc)
                val macro = macros.find { it.id == macroId }
                if (macro != null) {
                    val lastRun = macro.lastRun ?: 0
                    val daysSinceRun = (System.currentTimeMillis() - lastRun) / (1000 * 60 * 60 * 24)
                    if (daysSinceRun > 14) {
                        predictions.add(Prediction(
                            predictionId = "stale_macro_$macroId",
                            timestamp = System.currentTimeMillis(),
                            type = Prediction.PredictionType.MACRO_STALE,
                            description = "Macro '$macroId' not run for $daysSinceRun days - steps may be invalid",
                            probability = minOf(0.2 + daysSinceRun * 0.01, 0.7),
                            impact = Prediction.Impact.MODERATE,
                            timeframe = Prediction.Timeframe.MEDIUM_TERM,
                            indicators = listOf("Macro: $macroId", "Last run: $daysSinceRun days ago"),
                            recommendedActions = listOf(
                                RecommendedAction("test_macro", "Test macro in dry-run mode", RecommendedAction.Priority.MEDIUM, RecommendedAction.Effort.LOW),
                                RecommendedAction("update_macro", "Re-record macro steps", RecommendedAction.Priority.LOW, RecommendedAction.Effort.MEDIUM)
                            ),
                            confidence = 0.65
                        ))
                    }
                }
            }
        }

        return predictions
    }

    private fun predictDeviceStateChanges(context: PredictiveContext): List<Prediction> {
        val predictions = mutableListOf<Prediction>()

        // Screen off prediction
        if (!context.isScreenOn) {
            predictions.add(Prediction(
                predictionId = "device_screen_off",
                timestamp = System.currentTimeMillis(),
                type = Prediction.PredictionType.DEVICE_STATE_CHANGE,
                description = "Screen is off - UI automation and screen verification will fail",
                probability = 0.9,
                impact = Prediction.Impact.MAJOR,
                timeframe = Prediction.Timeframe.IMMEDIATE,
                indicators = listOf("Screen state: OFF"),
                recommendedActions = listOf(
                    RecommendedAction("wake_screen", "Wake device before UI steps", RecommendedAction.Priority.CRITICAL, RecommendedAction.Effort.TRIVIAL),
                    RecommendedAction("use_non_ui_tools", "Use non-UI tools where possible", RecommendedAction.Priority.HIGH, RecommendedAction.Effort.LOW)
                ),
                confidence = 0.95
            ))
        }

        // App background prediction
        if (context.currentAppPackage != null && !context.isAppInForeground) {
            predictions.add(Prediction(
                predictionId = "device_app_background",
                timestamp = System.currentTimeMillis(),
                type = Prediction.PredictionType.DEVICE_STATE_CHANGE,
                description = "Target app '${context.currentAppPackage}' is not in foreground",
                probability = 0.7,
                impact = Prediction.Impact.MODERATE,
                timeframe = Prediction.Timeframe.IMMEDIATE,
                indicators = listOf("App: ${context.currentAppPackage}", "State: background"),
                recommendedActions = listOf(
                    RecommendedAction("bring_to_foreground", "Use open_app to bring app to foreground", RecommendedAction.Priority.HIGH, RecommendedAction.Effort.TRIVIAL)
                ),
                confidence = 0.8
            ))
        }

        // Doze mode prediction
        if (context.isDeviceIdle) {
            predictions.add(Prediction(
                predictionId = "device_doze",
                timestamp = System.currentTimeMillis(),
                type = Prediction.PredictionType.DEVICE_STATE_CHANGE,
                description = "Device in doze/idle mode - network and alarms may be deferred",
                probability = 0.6,
                impact = Prediction.Impact.MODERATE,
                timeframe = Prediction.Timeframe.SHORT_TERM,
                indicators = listOf("Device idle: true"),
                recommendedActions = listOf(
                    RecommendedAction("whitelist_app", "Ensure app is battery optimization whitelisted", RecommendedAction.Priority.MEDIUM, RecommendedAction.Effort.LOW),
                    RecommendedAction("use_high_priority", "Use high-priority Firebase messages if needed", RecommendedAction.Priority.LOW, RecommendedAction.Effort.MEDIUM)
                ),
                confidence = 0.65
            ))
        }

        return predictions
    }

    private fun predictUserAvailability(context: PredictiveContext): List<Prediction> {
        val predictions = mutableListOf<Prediction>()

        // Quiet hours
        val autonomySettings = OrionAutonomySettings.read(svc)
        val now = java.util.Calendar.getInstance()
        val hour = now.get(java.util.Calendar.HOUR_OF_DAY)
        val inQuietHours = hour >= autonomySettings.quietHoursStart || hour < autonomySettings.quietHoursEnd

        if (inQuietHours && autonomySettings.proactiveEnabled) {
            predictions.add(Prediction(
                predictionId = "user_quiet_hours",
                timestamp = System.currentTimeMillis(),
                type = Prediction.PredictionType.USER_UNAVAILABLE,
                description = "Currently in quiet hours ($autonomySettings.quietHoursStart:00-$autonomySettings.quietHoursEnd:00) - proactive notifications suppressed",
                probability = 0.8,
                impact = Prediction.Impact.MINOR,
                timeframe = Prediction.Timeframe.IMMEDIATE,
                indicators = listOf("Hour: $hour", "Quiet hours: ${autonomySettings.quietHoursStart}-${autonomySettings.quietHoursEnd}"),
                recommendedActions = listOf(
                    RecommendedAction("defer_proactive", "Defer proactive interruptions until quiet hours end", RecommendedAction.Priority.MEDIUM, RecommendedAction.Effort.TRIVIAL)
                ),
                confidence = 0.9
            ))
        }

        // User inactive
        if (context.userInactiveMinutes > 30) {
            predictions.add(Prediction(
                predictionId = "user_inactive",
                timestamp = System.currentTimeMillis(),
                type = Prediction.PredictionType.USER_UNAVAILABLE,
                description = "User inactive for ${context.userInactiveMinutes} minutes - may not respond to confirmations",
                probability = 0.6,
                impact = Prediction.Impact.MODERATE,
                timeframe = Prediction.Timeframe.SHORT_TERM,
                indicators = listOf("Inactive: ${context.userInactiveMinutes} min"),
                recommendedActions = listOf(
                    RecommendedAction("extend_timeouts", "Extend confirmation timeouts", RecommendedAction.Priority.MEDIUM, RecommendedAction.Effort.TRIVIAL),
                    RecommendedAction("defer_confirmations", "Defer non-critical confirmations", RecommendedAction.Priority.MEDIUM, RecommendedAction.Effort.TRIVIAL)
                ),
                confidence = 0.7
            ))
        }

        return predictions
    }

    private fun predictExternalApiErrors(
        plan: OrionPlanningEngine.Plan,
        context: PredictiveContext
    ): List<Prediction> {
        val predictions = mutableListOf<Prediction>()

        val externalApiTools = setOf("trading_place_order", "trading_portfolio", "trading_balance", "trading_ticker", "trading_market_info", "weather", "live_web_search", "live_news", "live_sports")

        for (node in plan.nodes.filter { it.tool in externalApiTools }) {
            val tool = node.tool
            val historicalErrorRate = recall.getExternalApiErrorRate(tool)

            if (historicalErrorRate > 0.1) {
                predictions.add(Prediction(
                    predictionId = "api_error_${tool}_${node.id}",
                    timestamp = System.currentTimeMillis(),
                    type = Prediction.PredictionType.EXTERNAL_API_ERROR,
                    description = "External API for '$tool' has ${String.format("%.0f%%", historicalErrorRate * 100)} error rate",
                    probability = historicalErrorRate,
                    impact = if (tool.startsWith("trading_")) Prediction.Impact.CATASTROPHIC else Prediction.Impact.MODERATE,
                    timeframe = Prediction.Timeframe.IMMEDIATE,
                    indicators = listOf("Tool: $tool", "Historical error rate: ${String.format("%.0f%%", historicalErrorRate * 100)}"),
                    recommendedActions = listOf(
                        RecommendedAction("retry_with_backoff", "Implement exponential backoff", RecommendedAction.Priority.HIGH, RecommendedAction.Effort.TRIVIAL),
                        RecommendedAction("use_cached", "Use cached data if available", RecommendedAction.Priority.MEDIUM, RecommendedAction.Effort.LOW),
                        RecommendedAction("fallback_api", "Switch to alternative API provider", RecommendedAction.Priority.MEDIUM, RecommendedAction.Effort.MEDIUM)
                    ),
                    confidence = 0.8
                ))
            }
        }

        return predictions
    }

    private fun detectCycles(nodes: List<OrionPlanningEngine.PlanNode>, edges: List<OrionPlanningEngine.PlanEdge>): Boolean {
        val graph = mutableMapOf<String, MutableList<String>>()
        for (node in nodes) graph[node.id] = mutableListOf()
        for (edge in edges) {
            if (edge.type == OrionPlanningEngine.PlanEdge.EdgeType.DEPENDENCY) {
                graph[edge.from]?.add(edge.to)
            }
        }

        val visited = mutableSetOf<String>()
        val recStack = mutableSetOf<String>()

        fun dfs(node: String): Boolean {
            if (node !in graph) return false
            if (node in recStack) return true
            if (node in visited) return false

            visited.add(node)
            recStack.add(node)
            for (neighbor in graph[node]!!) {
                if (dfs(neighbor)) return true
            }
            recStack.remove(node)
            return false
        }

        for (node in graph.keys) {
            if (dfs(node)) return true
        }
        return false
    }

    private fun estimateBatteryDrain(plan: OrionPlanningEngine.Plan): Int {
        // Rough estimate: each step drains ~0.5-2% depending on tool
        var drain = 0
        for (node in plan.nodes) {
            drain += when (node.tool) {
                startsWith("browser_") -> 2
                "python_exec" -> 1
                "web_search" -> 1
                "trading_place_order" -> 1
                "open_app" -> 1
                "shell" -> 1
                else -> 0
            }
        }
        return drain.coerceAtMost(50)
    }

    private fun impactWeight(impact: Prediction.Impact): Double = when (impact) {
        Prediction.Impact.NEGLIGIBLE -> 0.1
        Prediction.Impact.MINOR -> 0.3
        Prediction.Impact.MODERATE -> 0.5
        Prediction.Impact.MAJOR -> 0.8
        Prediction.Impact.CATASTROPHIC -> 1.0
    }

    private fun calculateOverallRisk(predictions: List<Prediction>): Double {
        if (predictions.isEmpty()) return 0.0
        val totalRisk = predictions.sumOf { it.probability * impactWeight(it.impact) }
        return (totalRisk / predictions.size).coerceIn(0.0, 1.0)
    }

    private fun buildMitigationPlan(
        predictions: List<Prediction>,
        intent: OrionIntentIntelligence.Intent
    ): MitigationPlan {
        val preemptiveActions = mutableListOf<String>()
        val contingencyStrategies = mutableMapOf<String, String>()
        val monitoringRules = mutableListOf<MonitoringRule>()

        for (prediction in predictions) {
            // Preemptive actions for high probability predictions
            if (prediction.probability > 0.5) {
                for (action in prediction.recommendedActions.take(1)) {
                    preemptiveActions.add("[PRE] ${action.action}: ${action.description}")
                }
            }

            // Contingency strategies
            val fallbackStrategy = when (prediction.type) {
                Prediction.PredictionType.CAPABILITY_GAP -> "acquire_capability"
                Prediction.PredictionType.RESOURCE_EXHAUSTION -> "reduce_scope"
                Prediction.PredictionType.PERMISSION_DENIED -> "request_confirmation"
                Prediction.PredictionType.NETWORK_FAILURE -> "use_offline_fallback"
                Prediction.PredictionType.EXECUTION_TIMEOUT -> "increase_timeout"
                Prediction.PredictionType.VERIFICATION_FAILURE -> "change_verification"
                Prediction.PredictionType.DEPENDENCY_BLOCK -> "replan"
                Prediction.PredictionType.SKILL_OBSOLETE -> "test_skill"
                Prediction.PredictionType.MACRO_STALE -> "test_macro"
                Prediction.PredictionType.DEVICE_STATE_CHANGE -> "wake_screen"
                Prediction.PredictionType.USER_UNAVAILABLE -> "extend_timeouts"
                Prediction.PredictionType.EXTERNAL_API_ERROR -> "retry_with_backoff"
            }
            contingencyStrategies[prediction.predictionId] = fallbackStrategy

            // Monitoring rules for medium probability predictions
            if (prediction.probability > 0.3 && prediction.probability <= 0.5) {
                monitoringRules.add(MonitoringRule(
                    ruleId = "monitor_${prediction.predictionId}",
                    condition = prediction.description,
                    checkIntervalMs = when (prediction.timeframe) {
                        Prediction.Timeframe.IMMEDIATE -> 5000
                        Prediction.Timeframe.SHORT_TERM -> 30000
                        Prediction.Timeframe.MEDIUM_TERM -> 120000
                        Prediction.Timeframe.LONG_TERM -> 600000
                    },
                    actionOnTrigger = "Execute contingency: $fallbackStrategy"
                ))
            }
        }

        return MitigationPlan(
            preemptiveActions = preemptiveActions.distinct(),
            contingencyStrategies = contingencyStrategies,
            monitoringRules = monitoringRules
        )
    }
}

/**
 * Context for predictive intelligence.
 */
data class PredictiveContext(
    val deviceContext: DeviceContext? = null,
    val availableStorageMb: Int? = null,
    val isScreenOn: Boolean = true,
    val currentAppPackage: String? = null,
    val isAppInForeground: Boolean = false,
    val isDeviceIdle: Boolean = false,
    val userInactiveMinutes: Int = 0
)
