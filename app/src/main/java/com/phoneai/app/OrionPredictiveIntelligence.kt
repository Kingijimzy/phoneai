package com.phoneai.app

/**
 * Orion Predictive Intelligence
 *
 * Inspects a concrete plan against real device state, capability inventory and Cerebro
 * experience to predict which steps are likely to fail — then pre-arms a mitigation and a
 * contingency per prediction. Predictions never stand in for recovery: they only change what
 * the runtime watches for and which alternative it reaches for first.
 */
internal class OrionPredictiveIntelligence(
    private val svc: PhoneAiService
) {

    enum class ObstacleType {
        CAPABILITY_MISSING, CAPABILITY_DEGRADED, PERMISSION_MISSING, NETWORK_UNAVAILABLE,
        SCREEN_OFF, LOW_BATTERY, LOW_STORAGE, PROVIDER_UNREACHABLE, AMBIGUOUS_TARGET,
        VERIFICATION_UNCERTAIN, REPEATED_FAILURE_HISTORY, NOVEL_TERRITORY
    }

    enum class Severity { INFO, LOW, MEDIUM, HIGH }

    data class Obstacle(
        val type: ObstacleType,
        val stepId: String?,
        val description: String,
        val severity: Severity,
        val mitigation: String,
        val contingencyTool: String? = null
    )

    data class ObstacleReport(
        val predictedObstacles: List<Obstacle>,
        val mitigationPlan: List<String>,
        val contingencyStrategies: List<String>,
        val monitoringRules: List<String>
    ) {
        val hasBlockingRisk: Boolean get() = predictedObstacles.any { it.severity == Severity.HIGH }
    }

    fun predictObstacles(
        plan: OrionPlanningEngine.ExecutionPlan?,
        intent: OrionIntentIntelligence.Intent,
        context: OrionContextIntegration.UnifiedContext?
    ): ObstacleReport {
        val obstacles = mutableListOf<Obstacle>()
        val mitigations = mutableListOf<String>()
        val contingencies = mutableListOf<String>()
        val monitoring = mutableListOf<String>()

        val steps = plan?.steps ?: emptyList()
        val capabilities = context?.capabilities
        val device = context?.device
        val memory = context?.memory

        // ---- capability gaps, per step ----
        for (step in steps) {
            if (capabilities == null) break
            val health = capabilities.health[step.tool]
            when {
                health == null -> {
                    obstacles += Obstacle(
                        ObstacleType.CAPABILITY_MISSING, step.stepId,
                        "'${step.tool}' is not registered in the capability inventory",
                        Severity.HIGH,
                        "Replan this step via an available alternative before dispatch"
                    )
                    contingencies += "${step.stepId}: substitute a registered tool for '${step.tool}'"
                }
                !health.usable -> {
                    val permission = health.reason?.contains("permission", ignoreCase = true) == true
                    obstacles += Obstacle(
                        if (permission) ObstacleType.PERMISSION_MISSING else ObstacleType.CAPABILITY_MISSING,
                        step.stepId,
                        "'${step.tool}' is not usable: ${health.reason ?: health.status}",
                        Severity.HIGH,
                        if (permission) "Name the exact Android permission and stop before dispatch"
                        else "Skip or replan this step"
                    )
                }
                health.status == "AVAILABLE_LIMITED" -> {
                    obstacles += Obstacle(
                        ObstacleType.CAPABILITY_DEGRADED, step.stepId,
                        "'${step.tool}' is only partially verified (${health.reason ?: "not live-tested"})",
                        Severity.MEDIUM,
                        "Attempt it, but require explicit verification evidence before continuing"
                    )
                    monitoring += "watch ${step.stepId}: ${step.tool} is not live-tested"
                }
            }
        }

        // ---- network dependence ----
        val networkTools = steps.filter { it.tool.startsWith("browser_") || it.tool in NETWORK_TOOLS }
        if (networkTools.isNotEmpty() && device != null && device.networkType == null) {
            obstacles += Obstacle(
                ObstacleType.NETWORK_UNAVAILABLE, networkTools.first().stepId,
                "No usable network; ${networkTools.size} step(s) require connectivity",
                Severity.HIGH,
                "Tell the owner the network is down instead of retrying a dead request"
            )
            contingencies += "queue the network steps until connectivity returns"
        }

        // ---- screen state for UI steps ----
        val uiSteps = steps.filter { it.tool in UI_TOOLS }
        if (uiSteps.isNotEmpty() && device != null) {
            if (device.screenState == OrionContextIntegration.DeviceContext.ScreenState.OFF) {
                obstacles += Obstacle(
                    ObstacleType.SCREEN_OFF, uiSteps.first().stepId,
                    "Screen is off; ${uiSteps.size} UI step(s) will likely fail or act blindly",
                    Severity.HIGH,
                    "Wake the device and re-read screen state before the first UI action"
                )
                mitigations += "wake + read_screen before UI dispatch"
            }
            if (device.batteryLevel != null && device.batteryLevel < 10 && !device.isCharging) {
                obstacles += Obstacle(
                    ObstacleType.LOW_BATTERY, null,
                    "Battery at ${device.batteryLevel}% and not charging",
                    Severity.MEDIUM,
                    "Keep the plan short; avoid long-running compute steps"
                )
            }
        }

        // ---- storage for code/heavy work ----
        if (steps.any { it.tool in HEAVY_TOOLS }) {
            val freeMb = runCatching {
                val stat = android.os.StatFs(svc.cacheDir.path)
                (stat.availableBlocksLong * stat.blockSizeLong / (1024 * 1024)).toInt()
            }.getOrDefault(-1)
            if (freeMb in 0..200) {
                obstacles += Obstacle(
                    ObstacleType.LOW_STORAGE, null,
                    "Only ${freeMb}MB of storage available",
                    Severity.MEDIUM,
                    "Write small artefacts; clean workspace before large output"
                )
            }
        }

        // ---- ambiguous target ----
        val personNeeded = steps.any { it.argsTemplate.keys.any { k -> k == "contact" } }
        if (personNeeded) {
            val resolved = intent.entityRefs.any { it.resolvedFromMemory }
            if (!resolved) {
                obstacles += Obstacle(
                    ObstacleType.AMBIGUOUS_TARGET, steps.firstOrNull { "contact" in it.argsTemplate.keys }?.stepId,
                    "The recipient could not be resolved from memory; dispatch may pick the wrong contact",
                    Severity.HIGH,
                    "Resolve the contact explicitly (resolve_contact) before sending"
                )
                contingencies += "resolve_contact before send_message"
            }
        }

        // ---- ambiguous target for generic click on natural-language target ----
        if (steps.any { it.tool == "click" && it.argsTemplate["target"]?.length ?: 0 > 0 }) {
            obstacles += Obstacle(
                ObstacleType.AMBIGUOUS_TARGET, steps.first { it.tool == "click" }.stepId,
                "Click target was derived from natural language, not from a live screen element",
                Severity.MEDIUM,
                "Read the screen first and click a grounded element instead of the literal phrase"
            )
        }

        // ---- verification uncertainty ----
        if (steps.isNotEmpty() && steps.none { it.tool in VERIFIER_TOOLS }) {
            obstacles += Obstacle(
                ObstacleType.VERIFICATION_UNCERTAIN, null,
                "Plan has no dedicated verification step; completion would rest on tool success alone",
                Severity.MEDIUM,
                "Add a verify_action / verify_send step before declaring the objective done"
            )
            monitoring += "completion gate requires objective-level evidence, not dispatch success"
        }

        // ---- repeated failure history ----
        if (memory != null) {
            for (step in steps) {
                val past = memory.recentFailures.firstOrNull { it.startsWith(step.tool) }
                if (past != null) {
                    obstacles += Obstacle(
                        ObstacleType.REPEATED_FAILURE_HISTORY, step.stepId,
                        "'${step.tool}' failed before on a similar objective: ${past.take(120)}",
                        Severity.MEDIUM,
                        "Start with the alternative that worked last time, not the same call",
                        contingencyTool = step.failureAlternatives.firstOrNull()
                    )
                }
            }
            if (memory.recalledMemories.isEmpty() && memory.recallWasAttempted) {
                obstacles += Obstacle(
                    ObstacleType.NOVEL_TERRITORY, null,
                    "No prior memory matched this objective; behaviour is untested here",
                    Severity.LOW,
                    "Prefer read-only inspection first and verify more aggressively"
                )
            }
        }

        // ---- urgent but long plan ----
        if ((intent.urgency == OrionIntentIntelligence.Urgency.HIGH ||
                intent.urgency == OrionIntentIntelligence.Urgency.CRITICAL) && steps.size > 4) {
            obstacles += Obstacle(
                ObstacleType.AMBIGUOUS_TARGET, null,
                "Urgent objective with a ${steps.size}-step plan; time pressure can truncate verification",
                Severity.MEDIUM,
                "Do not skip the verification step to save time"
            )
            monitoring += "urgent run: verify the final step before reporting success"
        }

        val report = ObstacleReport(
            predictedObstacles = obstacles.sortedByDescending { it.severity.ordinal },
            mitigationPlan = mitigations.distinct(),
            contingencyStrategies = contingencies.distinct(),
            monitoringRules = monitoring.distinct()
        )

        if (report.predictedObstacles.isEmpty()) {
            return report.copy(
                predictedObstacles = listOf(
                    Obstacle(
                        ObstacleType.NOVEL_TERRITORY, null,
                        "No specific obstacle predicted for this plan",
                        Severity.INFO,
                        "Proceed and rely on per-step verification"
                    )
                )
            )
        }
        return report
    }

    private companion object {
        val UI_TOOLS = setOf("open_app", "click", "type_text", "swipe", "send_message", "read_screen")
        val NETWORK_TOOLS = setOf("live_web_search", "web_search", "research_web", "fetch_url", "live_news", "ask_agent")
        val HEAVY_TOOLS = setOf("python_exec", "shell", "extension_run")
        val VERIFIER_TOOLS = setOf("verify_action", "verify_send")
    }
}