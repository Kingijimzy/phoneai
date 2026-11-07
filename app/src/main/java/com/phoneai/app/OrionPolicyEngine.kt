package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Orion Policy Engine
 *
 * Central policy enforcement for decisions and actions.
 * Evaluates constraints, permissions, safety, and governance rules.
 */
class OrionPolicyEngine(
    private val svc: PhoneAiService,
    private val capabilityDiscovery: OrionCapabilityDiscovery,
    private val safetyGate: SafetyGate,
    private val toolGate: ToolGate,
    private val agentPolicy: AgentPolicy,
    private val shellPolicy: ShellPolicy
) {

    data class PolicyCheck(
        val checkId: String,
        val category: Category,
        val target: String, // tool, capability, action, etc.
        val status: Status,
        val message: String,
        val severity: Severity,
        val metadata: Map<String, String> = emptyMap()
    ) {
        enum class Category {
            CAPABILITY,       // Tool/capability availability
            PERMISSION,       // Android permission, user approval
            SAFETY,           // SafetyGate checks
            SCOPE,            // Tool scope validation
            RESOURCE,         // Budget, quota, battery
            SECURITY,         // Sensitive operations
            GOVERNANCE,       // Trading, irreversible actions
            QUALITY,          // Verification, evidence requirements
            COMPLIANCE        // Policy compliance
        }

        enum class Status { PASS, FAIL, SOFT_VIOLATION, WARNING, BLOCKED }

        enum class Severity { INFO, LOW, MEDIUM, HIGH, CRITICAL }
    }

    data class PolicyEvaluation(
        val overallStatus: OverallStatus,
        val checks: List<PolicyCheck>,
        val blockedActions: List<String>,
        val requiredApprovals: List<RequiredApproval>,
        val warnings: List<String>,
        val recommendations: List<String>
    ) {
        enum class OverallStatus { ALLOW, ALLOW_WITH_CONDITIONS, REQUIRE_APPROVAL, BLOCK }
    }

    data class RequiredApproval(
        val approvalId: String,
        val type: ApprovalType,
        val description: String,
        val tool: String,
        val reason: String
    ) {
        enum class ApprovalType { TRADING_ORDER, SYSTEM_CHANGE, IRREVERSIBLE_ACTION, SENSITIVE_DATA, EXTERNAL_API }
    }

    /**
     * Check all policy constraints for an intent and reasoning result.
     */
    fun checkConstraints(
        intent: OrionIntentIntelligence.Intent,
        reasoningResult: OrionReasoningEngine.ReasoningResult
    ): List<String> {
        val checks = mutableListOf<PolicyCheck>()

        // Capability checks
        checks.addAll(checkCapabilities(reasoningResult.selectedStrategy))

        // Permission checks
        checks.addAll(checkPermissions(intent, reasoningResult))

        // Safety checks
        checks.addAll(checkSafety(intent, reasoningResult))

        // Scope checks
        checks.addAll(checkScope(reasoningResult))

        // Resource checks
        checks.addAll(checkResources(intent, reasoningResult))

        // Security checks
        checks.addAll(checkSecurity(intent, reasoningResult))

        // Governance checks
        checks.addAll(checkGovernance(intent, reasoningResult))

        // Quality checks
        checks.addAll(checkQuality(reasoningResult))

        // Compliance checks
        checks.addAll(checkCompliance(intent, reasoningResult))

        // Convert to string format for decision engine
        return checks.map { check ->
            val prefix = when (check.status) {
                PolicyCheck.Status.PASS -> "PASS"
                PolicyCheck.Status.FAIL -> "FAIL"
                PolicyCheck.Status.SOFT_VIOLATION -> "SOFT_VIOLATION"
                PolicyCheck.Status.WARNING -> "WARNING"
                PolicyCheck.Status.BLOCKED -> "BLOCKED"
            }
            "${prefix}: ${check.category} - ${check.target} - ${check.message}"
        }
    }

    /**
     * Evaluate a specific tool execution against policies.
     */
    fun evaluateToolExecution(
        tool: String,
        args: JSONObject,
        context: ExecutionContext
    ): PolicyEvaluation {
        val checks = mutableListOf<PolicyCheck>()

        // ToolGate preflight
        val preflight = toolGate.preflightDenial(svc, tool)
        if (preflight != null) {
            checks.add(PolicyCheck(
                checkId = "toolgate_${tool}",
                category = PolicyCheck.Category.CAPABILITY,
                target = tool,
                status = PolicyCheck.Status.BLOCKED,
                message = preflight,
                severity = PolicyCheck.Severity.CRITICAL
            ))
        }

        // Capability availability
        val capability = capabilityDiscovery.getAvailableCapabilities(svc).firstOrNull { it.name == tool }
        if (capability == null) {
            checks.add(PolicyCheck(
                checkId = "capability_${tool}",
                category = PolicyCheck.Category.CAPABILITY,
                target = tool,
                status = PolicyCheck.Status.FAIL,
                message = "Capability not available: $tool",
                severity = PolicyCheck.Severity.HIGH
            ))
        }

        // Safety gate
        val safetyResult = safetyGate.checkTool(tool, args)
        if (safetyResult.blocked) {
            checks.add(PolicyCheck(
                checkId = "safety_${tool}",
                category = PolicyCheck.Category.SAFETY,
                target = tool,
                status = PolicyCheck.Status.BLOCKED,
                message = safetyResult.reason ?: "Safety check failed",
                severity = PolicyCheck.Severity.CRITICAL
            ))
        } else if (safetyResult.warning != null) {
            checks.add(PolicyCheck(
                checkId = "safety_warn_${tool}",
                category = PolicyCheck.Category.SAFETY,
                target = tool,
                status = PolicyCheck.Status.WARNING,
                message = safetyResult.warning!!,
                severity = PolicyCheck.Severity.MEDIUM
            ))
        }

        // Shell policy
        if (tool == "shell") {
            val command = args.optString("command")
            val risk = shellPolicy.classify(command)
            if (risk == ShellRisk.BLOCKED) {
                checks.add(PolicyCheck(
                    checkId = "shell_${command.hashCode()}",
                    category = PolicyCheck.Category.SAFETY,
                    target = tool,
                    status = PolicyCheck.Status.BLOCKED,
                    message = "Shell command blocked: $command",
                    severity = PolicyCheck.Severity.CRITICAL
                ))
            } else if (risk == ShellRisk.HIGH_RISK) {
                checks.add(PolicyCheck(
                    checkId = "shell_warn_${command.hashCode()}",
                    category = PolicyCheck.Category.SAFETY,
                    target = tool,
                    status = PolicyCheck.Status.WARNING,
                    message = "High-risk shell command: $command",
                    severity = PolicyCheck.Severity.HIGH
                ))
            }
        }

        // Irreversible action confirmation
        if (IrreversibleActionConfirmationPolicy.requiresConfirmation(tool)) {
            checks.add(PolicyCheck(
                checkId = "irreversible_${tool}",
                category = PolicyCheck.Category.GOVERNANCE,
                target = tool,
                status = PolicyCheck.Status.FAIL, // Requires approval
                message = "Irreversible action requires user confirmation: $tool",
                severity = PolicyCheck.Severity.CRITICAL,
                metadata = mapOf("requiresConfirmation" to "true")
            ))
        }

        // Trading approval
        if (tool == "trading_place_order") {
            checks.add(PolicyCheck(
                checkId = "trading_approval",
                category = PolicyCheck.Category.GOVERNANCE,
                target = tool,
                status = PolicyCheck.Status.FAIL, // Requires approval
                message = "Trading order requires explicit user authorization",
                severity = PolicyCheck.Severity.CRITICAL,
                metadata = mapOf("requiresConfirmation" to "true", "approvalType" to "TRADING_ORDER")
            ))
        }

        // Scope validation
        val scopeValidation = AgentDispatchPolicy.validateScope(tool, args)
        if (scopeValidation != null) {
            checks.add(PolicyCheck(
                checkId = "scope_${tool}",
                category = PolicyCheck.Category.SCOPE,
                target = tool,
                status = PolicyCheck.Status.FAIL,
                message = scopeValidation,
                severity = PolicyCheck.Severity.HIGH
            ))
        }

        // Determine overall status
        val hasBlocked = checks.any { it.status == PolicyCheck.Status.BLOCKED }
        val hasFail = checks.any { it.status == PolicyCheck.Status.FAIL }
        val hasSoftViolation = checks.any { it.status == PolicyCheck.Status.SOFT_VIOLATION }
        val hasWarning = checks.any { it.status == PolicyCheck.Status.WARNING }

        val overallStatus = when {
            hasBlocked -> PolicyEvaluation.OverallStatus.BLOCK
            hasFail -> PolicyEvaluation.OverallStatus.REQUIRE_APPROVAL
            hasSoftViolation -> PolicyEvaluation.OverallStatus.ALLOW_WITH_CONDITIONS
            hasWarning -> PolicyEvaluation.OverallStatus.ALLOW_WITH_CONDITIONS
            else -> PolicyEvaluation.OverallStatus.ALLOW
        }

        val blockedActions = checks.filter { it.status == PolicyCheck.Status.BLOCKED }.map { it.target }
        val requiredApprovals = checks.filter { it.status == PolicyCheck.Status.FAIL && it.metadata["requiresConfirmation"] == "true" }
            .map { check ->
                val approvalType = check.metadata["approvalType"]?.let { RequiredApproval.ApprovalType.valueOf(it) }
                    ?: when (check.target) {
                        "trading_place_order" -> RequiredApproval.ApprovalType.TRADING_ORDER
                        "computer_settings_restart", "computer_settings_shutdown", "computer_settings_wifi_toggle" -> RequiredApproval.ApprovalType.SYSTEM_CHANGE
                        else -> RequiredApproval.ApprovalType.IRREVERSIBLE_ACTION
                    }
                RequiredApproval(
                    approvalId = "approval_${System.currentTimeMillis()}_${check.target.hashCode()}",
                    type = approvalType,
                    description = check.message,
                    tool = check.target,
                    reason = check.message
                )
            }
        val warnings = checks.filter { it.status == PolicyCheck.Status.WARNING }.map { it.message }
        val recommendations = checks.filter { it.status == PolicyCheck.Status.SOFT_VIOLATION }.map { it.message }

        return PolicyEvaluation(
            overallStatus = overallStatus,
            checks = checks,
            blockedActions = blockedActions,
            requiredApprovals = requiredApprovals,
            warnings = warnings,
            recommendations = recommendations
        )
    }

    private fun checkCapabilities(strategy: OrionReasoningEngine.Strategy?): List<PolicyCheck> {
        val checks = mutableListOf<PolicyCheck>()
        strategy?.requiredCapabilities?.forEach { cap ->
            val available = capabilityDiscovery.getAvailableCapabilities(svc).map { it.name }.contains(cap)
            checks.add(PolicyCheck(
                checkId = "cap_$cap",
                category = PolicyCheck.Category.CAPABILITY,
                target = cap,
                status = if (available) PolicyCheck.Status.PASS else PolicyCheck.Status.FAIL,
                message = if (available) "Capability available" else "Capability missing: $cap",
                severity = if (available) PolicyCheck.Severity.INFO else PolicyCheck.Severity.HIGH
            ))
        }
        return checks
    }

    private fun checkPermissions(
        intent: OrionIntentIntelligence.Intent,
        reasoningResult: OrionReasoningEngine.ReasoningResult
    ): List<PolicyCheck> {
        val checks = mutableListOf<PolicyCheck>()

        // Trading permission
        if (intent.type == OrionIntentIntelligence.IntentType.TRADING) {
            checks.add(PolicyCheck(
                checkId = "perm_trading",
                category = PolicyCheck.Category.PERMISSION,
                target = "trading_place_order",
                status = PolicyCheck.Status.FAIL, // Requires approval
                message = "Trading requires explicit user authorization",
                severity = PolicyCheck.Severity.CRITICAL,
                metadata = mapOf("requiresConfirmation" to "true", "approvalType" to "TRADING_ORDER")
            ))
        }

        // System control permission
        if (intent.type == OrionIntentIntelligence.IntentType.SYSTEM_CONTROL) {
            val systemTools = setOf("computer_settings_restart", "computer_settings_shutdown", "computer_settings_wifi_toggle")
            for (tool in systemTools) {
                if (reasoningResult.selectedStrategy?.requiredCapabilities?.contains(tool) == true) {
                    checks.add(PolicyCheck(
                        checkId = "perm_system_$tool",
                        category = PolicyCheck.Category.PERMISSION,
                        target = tool,
                        status = PolicyCheck.Status.FAIL,
                        message = "System control requires user confirmation: $tool",
                        severity = PolicyCheck.Severity.CRITICAL,
                        metadata = mapOf("requiresConfirmation" to "true", "approvalType" to "SYSTEM_CHANGE")
                    ))
                }
            }
        }

        // Android permissions
        val androidPermTools = mapOf(
            "click" to "android.permission.ACCESSIBILITY",
            "open_app" to "android.permission.ACCESSIBILITY",
            "read_screen" to "android.permission.ACCESSIBILITY",
            "browser_navigate" to "android.permission.INTERNET",
            "web_search" to "android.permission.INTERNET",
            "send_message" to "android.permission.READ_CONTACTS",
            "call_phone" to "android.permission.CALL_PHONE"
        )

        for ((tool, perm) in androidPermTools) {
            if (reasoningResult.selectedStrategy?.requiredCapabilities?.contains(tool) == true) {
                val granted = androidx.core.content.ContextCompat.checkSelfPermission(svc, perm) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                checks.add(PolicyCheck(
                    checkId = "perm_android_$tool",
                    category = PolicyCheck.Category.PERMISSION,
                    target = tool,
                    status = if (granted) PolicyCheck.Status.PASS else PolicyCheck.Status.WARNING,
                    message = "Android permission $perm ${if (granted) "granted" else "not granted"} for $tool",
                    severity = if (granted) PolicyCheck.Severity.INFO else PolicyCheck.Severity.MEDIUM
                ))
            }
        }

        return checks
    }

    private fun checkSafety(
        intent: OrionIntentIntelligence.Intent,
        reasoningResult: OrionReasoningEngine.ReasoningResult
    ): List<PolicyCheck> {
        val checks = mutableListOf<PolicyCheck>()

        // Security constraints
        val securityConstraints = intent.constraints.filter { it.type == OrionIntentIntelligence.Constraint.ConstraintType.SECURITY }
        for (constraint in securityConstraints) {
            checks.add(PolicyCheck(
                checkId = "safety_${constraint.type.name}",
                category = PolicyCheck.Category.SAFETY,
                target = constraint.description,
                status = if (constraint.severity == OrionIntentIntelligence.Constraint.Severity.HARD) PolicyCheck.Status.FAIL else PolicyCheck.Status.WARNING,
                message = "Security constraint: ${constraint.description}",
                severity = if (constraint.severity == OrionIntentIntelligence.Constraint.Severity.HARD) PolicyCheck.Severity.CRITICAL else PolicyCheck.Severity.HIGH
            ))
        }

        // SafetyGate for selected strategy tools
        val strategy = reasoningResult.selectedStrategy
        strategy?.requiredCapabilities?.forEach { cap ->
            val safetyResult = safetyGate.checkTool(cap, JSONObject())
            if (safetyResult.blocked) {
                checks.add(PolicyCheck(
                    checkId = "safety_gate_$cap",
                    category = PolicyCheck.Category.SAFETY,
                    target = cap,
                    status = PolicyCheck.Status.BLOCKED,
                    message = safetyResult.reason ?: "SafetyGate blocked",
                    severity = PolicyCheck.Severity.CRITICAL
                ))
            }
        }

        return checks
    }

    private fun checkScope(reasoningResult: OrionReasoningEngine.ReasoningResult): List<PolicyCheck> {
        val checks = mutableListOf<PolicyCheck>()

        val strategy = reasoningResult.selectedStrategy
        strategy?.requiredCapabilities?.forEach { cap ->
            val scopeValidation = AgentDispatchPolicy.validateScope(cap, JSONObject())
            if (scopeValidation != null) {
                checks.add(PolicyCheck(
                    checkId = "scope_$cap",
                    category = PolicyCheck.Category.SCOPE,
                    target = cap,
                    status = PolicyCheck.Status.FAIL,
                    message = scopeValidation,
                    severity = PolicyCheck.Severity.HIGH
                ))
            }
        }

        return checks
    }

    private fun checkResources(
        intent: OrionIntentIntelligence.Intent,
        reasoningResult: OrionReasoningEngine.ReasoningResult
    ): List<PolicyCheck> {
        val checks = mutableListOf<PolicyCheck>()

        // Battery
        val deviceContext = OrionDeviceContextProvider.summary(svc)
        deviceContext?.batteryLevel?.let { battery ->
            val strategy = reasoningResult.selectedStrategy
            val estimatedDuration = strategy?.estimatedDurationMs ?: 0L
            if (battery < 20 && estimatedDuration > 60000) {
                checks.add(PolicyCheck(
                    checkId = "resource_battery",
                    category = PolicyCheck.Category.RESOURCE,
                    target = "battery",
                    status = PolicyCheck.Status.WARNING,
                    message = "Low battery ($battery%) for long-running task (${estimatedDuration / 1000}s)",
                    severity = PolicyCheck.Severity.HIGH
                ))
            }
        }

        // API quota
        val autonomySettings = OrionAutonomySettings.read(svc)
        val estimatedRequests = strategy?.requiredCapabilities?.size ?: 0
        if (estimatedRequests > autonomySettings.maxAiRequests * 0.8) {
            checks.add(PolicyCheck(
                checkId = "resource_quota",
                category = PolicyCheck.Category.RESOURCE,
                target = "api_quota",
                status = PolicyCheck.Status.SOFT_VIOLATION,
                message = "Estimated $estimatedRequests requests may exceed quota (${autonomySettings.maxAiRequests})",
                severity = PolicyCheck.Severity.MEDIUM
            ))
        }

        // Parallel agents
        val parallelAgents = strategy?.approach == OrionReasoningEngine.Strategy.Approach.PARALLEL_DELEGATION
        if (parallelAgents) {
            val maxParallel = autonomySettings.maxParallelAgents
            if (maxParallel < 2) {
                checks.add(PolicyCheck(
                    checkId = "resource_parallel",
                    category = PolicyCheck.Category.RESOURCE,
                    target = "parallel_agents",
                    status = PolicyCheck.Status.SOFT_VIOLATION,
                    message = "Parallel delegation requires maxParallelAgents >= 2 (current: $maxParallel)",
                    severity = PolicyCheck.Severity.MEDIUM
                ))
            }
        }

        return checks
    }

    private fun checkSecurity(
        intent: OrionIntentIntelligence.Intent,
        reasoningResult: OrionReasoningEngine.ReasoningResult
    ): List<PolicyCheck> {
        val checks = mutableListOf<PolicyCheck>()

        // Check for sensitive data in intent
        val sensitivePatterns = listOf(
            "password" to "password",
            "credential" to "credential",
            "private key" to "private_key",
            "seed phrase" to "seed_phrase",
            "api key" to "api_key",
            "token" to "token"
        )

        for ((pattern, label) in sensitivePatterns) {
            if (intent.primaryObjective.contains(pattern, ignoreCase = true) ||
                intent.entities.values.any { it.contains(pattern, ignoreCase = true) }) {
                checks.add(PolicyCheck(
                    checkId = "security_$label",
                    category = PolicyCheck.Category.SECURITY,
                    target = label,
                    status = PolicyCheck.Status.WARNING,
                    message = "Sensitive data pattern detected: $label",
                    severity = PolicyCheck.Severity.HIGH
                ))
            }
        }

        // Delete/wipe operations
        if (intent.primaryObjective.contains("delete", ignoreCase = true) ||
            intent.primaryObjective.contains("wipe", ignoreCase = true) ||
            intent.primaryObjective.contains("remove all", ignoreCase = true)) {
            checks.add(PolicyCheck(
                checkId = "security_destructive",
                category = PolicyCheck.Category.SECURITY,
                target = "destructive_operation",
                status = PolicyCheck.Status.WARNING,
                message = "Destructive operation detected - ensure verification and confirmation",
                severity = PolicyCheck.Severity.HIGH
            ))
        }

        return checks
    }

    private fun checkGovernance(
        intent: OrionIntentIntelligence.Intent,
        reasoningResult: OrionReasoningEngine.ReasoningResult
    ): List<PolicyCheck> {
        val checks = mutableListOf<PolicyCheck>()

        // Trading governance
        if (intent.type == OrionIntentIntelligence.IntentType.TRADING) {
            checks.add(PolicyCheck(
                checkId = "gov_trading",
                category = PolicyCheck.Category.GOVERNANCE,
                target = "trading",
                status = PolicyCheck.Status.FAIL, // Requires approval
                message = "Trading operations require explicit user authorization and audit trail",
                severity = PolicyCheck.Severity.CRITICAL,
                metadata = mapOf("requiresConfirmation" to "true", "approvalType" to "TRADING_ORDER")
            ))
        }

        // Irreversible actions
        val irreversibleTools = setOf("computer_settings_restart", "computer_settings_shutdown", "computer_settings_wifi_toggle")
        for (tool in irreversibleTools) {
            if (reasoningResult.selectedStrategy?.requiredCapabilities?.contains(tool) == true) {
                checks.add(PolicyCheck(
                    checkId = "gov_irreversible_$tool",
                    category = PolicyCheck.Category.GOVERNANCE,
                    target = tool,
                    status = PolicyCheck.Status.FAIL,
                    message = "Irreversible system action requires confirmation: $tool",
                    severity = PolicyCheck.Severity.CRITICAL,
                    metadata = mapOf("requiresConfirmation" to "true", "approvalType" to "SYSTEM_CHANGE")
                ))
            }
        }

        return checks
    }

    private fun checkQuality(reasoningResult: OrionReasoningEngine.ReasoningResult): List<PolicyCheck> {
        val checks = mutableListOf<PolicyCheck>()

        // Verification requirement
        val strategy = reasoningResult.selectedStrategy
        if (strategy != null) {
            val hasVerification = strategy.requiredCapabilities.any { cap ->
                cap.startsWith("verify_") || cap == "read_screen"
            }
            if (!hasVerification && strategy.approach != OrionReasoningEngine.Strategy.Approach.DIRECT_TOOL) {
                checks.add(PolicyCheck(
                    checkId = "quality_verification",
                    category = PolicyCheck.Category.QUALITY,
                    target = "verification",
                    status = PolicyCheck.Status.SOFT_VIOLATION,
                    message = "Multi-step strategy should include explicit verification steps",
                    severity = PolicyCheck.Severity.MEDIUM
                ))
            }
        }

        // Confidence threshold
        if (reasoningResult.selectedStrategy?.confidence != null && reasoningResult.selectedStrategy!!.confidence < 0.5) {
            checks.add(PolicyCheck(
                checkId = "quality_confidence",
                category = PolicyCheck.Category.QUALITY,
                target = "confidence",
                status = PolicyCheck.Status.WARNING,
                message = "Selected strategy has low confidence (${String.format("%.0f%%", reasoningResult.selectedStrategy!!.confidence * 100)})",
                severity = PolicyCheck.Severity.MEDIUM
            ))
        }

        return checks
    }

    private fun checkCompliance(
        intent: OrionIntentIntelligence.Intent,
        reasoningResult: OrionReasoningEngine.ReasoningResult
    ): List<PolicyCheck> {
        val checks = mutableListOf<PolicyCheck>()

        // Hard constraints must be satisfied
        val hardConstraints = intent.constraints.filter { it.severity == OrionIntentIntelligence.Constraint.Severity.HARD }
        for (constraint in hardConstraints) {
            val satisfied = checkConstraintSatisfied(constraint, reasoningResult)
            checks.add(PolicyCheck(
                checkId = "compliance_${constraint.type.name}",
                category = PolicyCheck.Category.COMPLIANCE,
                target = constraint.description,
                status = if (satisfied) PolicyCheck.Status.PASS else PolicyCheck.Status.BLOCKED,
                message = "Hard constraint ${if (satisfied) "satisfied" else "violated"}: ${constraint.description}",
                severity = if (satisfied) PolicyCheck.Severity.INFO else PolicyCheck.Severity.CRITICAL
            ))
        }

        return checks
    }

    private fun checkConstraintSatisfied(
        constraint: OrionIntentIntelligence.Constraint,
        reasoningResult: OrionReasoningEngine.ReasoningResult
    ): Boolean {
        val strategy = reasoningResult.selectedStrategy ?: return false

        return when (constraint.type) {
            OrionIntentIntelligence.Constraint.ConstraintType.CAPABILITY -> {
                // Check if required capability is available
                val missing = constraint.description
                strategy.requiredCapabilities.none { it.contains(missing, ignoreCase = true) }
            }
            OrionIntentIntelligence.Constraint.ConstraintType.PERMISSION -> {
                // Check if permission is granted (simplified)
                true // Would need actual permission check
            }
            OrionIntentIntelligence.Constraint.ConstraintType.RESOURCE -> {
                // Check resource availability
                true
            }
            OrionIntentIntelligence.Constraint.ConstraintType.TIME -> {
                // Check if estimated duration fits deadline
                val deadlineMatch = Regex("(\\d+)\\s*(minute|hour|day)").find(constraint.description)
                deadlineMatch?.let { match ->
                    val amount = match.groupValues[1].toIntOrNull() ?: return true
                    val unit = match.groupValues[2]
                    val deadlineMs = when (unit) {
                        "minute" -> amount * 60_000L
                        "hour" -> amount * 3_600_000L
                        "day" -> amount * 86_400_000L
                        else -> return true
                    }
                    strategy.estimatedDurationMs <= deadlineMs
                } ?: true
            }
            else -> true
        }
    }
}

/**
 * Context for tool execution policy evaluation.
 */
data class ExecutionContext(
    val activeObjective: String? = null,
    val currentTask: String? = null,
    val missionHistory: List<OrionObjectiveGraph.MissionRecord> = emptyList(),
    val deviceState: String = "",
    val userPreferences: Map<String, String> = emptyMap()
)
