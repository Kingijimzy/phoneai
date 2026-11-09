package com.phoneai.app

import android.content.Context
import kotlinx.coroutines.*

/**
 * Phase 0C: Mission Ownership
 *
 * Creates and manages bounded specialist agents.
 * Specialists execute using the Central Brain's unified runtime with dynamic
 * capability expansion via OrionCapabilityBroker. The factory enforces
 * concurrency bounds and prevents runaway agent spawning.
 *
 * Ultron Doctrine:
 *   - "Specialists should receive the tools needed for their assignments."
 *   - "Specialists should never be trapped by unnecessarily narrow tool configurations."
 *   - "Dynamic specialist creation. Parallel execution. Shared mission context."
 *   - "Capability expansion. Delegation and result reconciliation."
 */
internal class OrionAgentFactory(
    private val context: Context,
    private val svc: PhoneAiService,
    private val ui: AgentUi,
    private val tools: Tools,
    private val localExecutor: Local,
    private val pythonExecutor: OrionPythonCapabilityExecutor,
    private val commandNim: Nim,
    private val controller: OrionMissionController,
    private val capabilityBroker: OrionCapabilityBroker,
    private val undoStack: OrionUndoStack
) {

    private val activeSessions = mutableMapOf<String, SpecialistSession>()
    private val sessionLock = Any()
    private val MAX_SESSION_DURATION_MS = 10 * 60 * 1000L // 10 minutes per specialist
    private val PROGRESS_POLL_INTERVAL_MS = 2000L

    /**
     * Create a new specialist session with scoped tools and context.
     * Specialists start with their default tools but can dynamically request
     * additional capabilities through the CapabilityBroker.
     *
     * ULTRON-STYLE: Specialists start with default tools but are NOT permanently
     * restricted. They can request additional capabilities at any time via
     * request_capabilities through the broker.
     */
    fun createSession(
        parentMissionId: String,
        specialism: OrionSpecialism,
        objective: String,
        allowedTools: Set<String>? = null,
        contextSnapshot: OrionContextIntegration.UnifiedContext? = null
    ): SpecialistSession {
        val sessionId = "spec_${specialism.id}_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}"

        // Build initial tool set: specialism defaults, optionally filtered by allowedTools
        // This is the STARTING set - specialists can request more via CapabilityBroker
        val initialTools = buildInitialToolSet(specialism, allowedTools)
        val scopedContext = buildScopedContext(contextSnapshot, initialTools, objective)

        val session = SpecialistSession(
            sessionId = sessionId,
            parentMissionId = parentMissionId,
            specialism = specialism,
            objective = objective,
            allowedTools = initialTools, // Starting tools - can expand via broker
            contextSnapshot = scopedContext,
            createdAt = System.currentTimeMillis(),
            status = SpecialistStatus.STARTING
        )

        synchronized(sessionLock) {
            activeSessions[sessionId] = session
        }

        // Start specialist execution in background
        controller.missionScope.launch {
            runSpecialist(session)
        }

        return session
    }

    /**
     * Collect a completed specialist's result.
     * Blocks until the specialist finishes or times out.
     */
    suspend fun collectSession(sessionId: String): OrionSpecialistReceipt {
        val session = synchronized(sessionLock) { activeSessions[sessionId] }
            ?: throw IllegalArgumentException("Specialist session $sessionId not found")

        // Wait for completion with timeout
        val startWait = SystemClock.elapsedRealtime()
        while (session.status != SpecialistStatus.COMPLETED &&
               session.status != SpecialistStatus.FAILED &&
               session.status != SpecialistStatus.CANCELLED &&
               SystemClock.elapsedRealtime() - startWait < 30_000) {
            delay(500)
        }

        val receipt = session.buildReceipt()
        synchronized(sessionLock) { activeSessions.remove(sessionId) }
        return receipt
    }

    /**
     * Terminate a specialist early.
     */
    suspend fun terminateSession(sessionId: String, reason: String): OrionSpecialistReceipt {
        val session = synchronized(sessionLock) { activeSessions[sessionId] }
            ?: throw IllegalArgumentException("Specialist session $sessionId not found")

        session.cancel(reason)
        // Wait briefly for cleanup
        var waited = 0L
        while (session.status != SpecialistStatus.CANCELLED && waited < 2000) {
            delay(100)
            waited += 100
        }
        val receipt = session.buildReceipt()
        synchronized(sessionLock) { activeSessions.remove(sessionId) }
        return receipt
    }

    /**
     * Get status of a specialist session.
     */
    fun getSessionStatus(sessionId: String): SpecialistStatus? {
        return synchronized(sessionLock) { activeSessions[sessionId]?.status }
    }

    /** List all active specialist sessions for a parent mission */
    fun getActiveSessions(parentMissionId: String): List<SpecialistSession> {
        return synchronized(sessionLock) {
            activeSessions.values.filter { it.parentMissionId == parentMissionId }.toList()
        }
    }

    /** Global concurrency check */
    fun canSpawnMore(parentMissionId: String): Boolean {
        return synchronized(sessionLock) {
            activeSessions.values.count { it.parentMissionId == parentMissionId && it.status != SpecialistStatus.COMPLETED && it.status != SpecialistStatus.FAILED && it.status != SpecialistStatus.CANCELLED } < controller.MAX_CONCURRENT_SPECIALISTS
        }
    }

    // ------------------------------------------------------------------------
    // PRIVATE: SPECIALIST EXECUTION
    // ------------------------------------------------------------------------

    private fun buildInitialToolSet(specialism: OrionSpecialism, allowedTools: Set<String>?): Set<String> {
        var tools = specialism.defaultTools
        allowedTools?.let { tools = tools.intersect(it) }
        // Filter only to what's actually registered (not by preflight denial — that's runtime)
        tools = tools.filter { name ->
            ToolRegistry.all.any { it.name == name }
        }
        return tools
    }

    private fun buildScopedContext(
        snapshot: OrionContextIntegration.UnifiedContext?,
        tools: Set<String>,
        objective: String
    ): OrionContextIntegration.UnifiedContext {
        // ULTRON-STYLE: Do NOT filter capabilities for specialists.
        // Specialists see the full capability ecosystem and can request expansion via broker.
        return snapshot ?: OrionContextIntegration(context.applicationContext).buildContext(
            command = objective,
            modality = "SPECIALIST",
            activeObjective = objective,
            sessionData = OrionContextIntegration.SessionData(objective, "SPECIALIST", objective, System.currentTimeMillis())
        )
    }

    /**
     * Phase 0C: Specialist execution using the Central Brain's unified runtime
     * with dynamic capability expansion via OrionCapabilityBroker.
     * Specialists start with initial tools but can request additional capabilities.
     *
     * ULTRON-STYLE: Specialists use the Central Brain's unified runtime which has
     * access to ALL registered capabilities. Tool restrictions are enforced at
     * dispatch time (Android perms, trading confirmation, irreversible actions),
     * not by pre-filtering the specialist's tool set.
     */
    private fun runSpecialist(session: SpecialistSession) {
        val startedAt = System.currentTimeMillis()
        val evidence = mutableListOf<String>()
        var toolCalls = 0
        var verdict = SpecialistVerdict.FAILED
        var result = "Specialist did not produce a result"

        // Specialist timeout: 10 minutes max
        val TIMEOUT_MS = 10 * 60 * 1000L

        try {
            withTimeout(TIMEOUT_MS) {
                synchronized(sessionLock) { session.status = SpecialistStatus.RUNNING }

                // Use the Central Brain's unified runtime with capability broker for dynamic expansion
                val specialistRuntime = controller.unifiedRuntime

                // Execute the specialist's objective through the decision pipeline
                val intent = specialistRuntime.analyzeCurrentIntent(session.objective)

                // Check for missing capabilities and attempt auto-acquisition
                val discovery = capabilityBroker.discoverMissingCapabilities(intent)
                if (discovery.canAutoResolve && discovery.acquisitionPlans.isNotEmpty()) {
                    evidence += "[CAPABILITY] Auto-resolving missing capabilities: ${discovery.missingCapabilities.joinToString(", ")}"
                    for (plan in discovery.acquisitionPlans) {
                        val acquired = capabilityBroker.executeAcquisitionPlan(plan)
                        if (acquired) {
                            evidence += "[CAPABILITY] Acquired: ${plan.capability}"
                        } else {
                            evidence += "[CAPABILITY] Failed to acquire: ${plan.capability} - ${plan.description}"
                        }
                    }
                }

                val decision = specialistRuntime.decideForIntent(intent)

                if (!decision.canProceed || decision.executionPlan == null || decision.executionPlan!!.steps.isEmpty()) {
                    result = "Specialist cannot proceed: ${decision.blocker ?: "no executable plan"}"
                    verdict = SpecialistVerdict.FAILED
                } else {
                    // Execute the plan using Central Brain runtime (all tools available)
                    val outcome = specialistRuntime.executePlan(
                        decision.executionPlan!!, decision,
                        specialistRuntime.predictObstaclesForPlan(decision.executionPlan!!, intent),
                        OrionUnifiedRuntime.Modality.COMMAND,
                        specialistRuntime.lastContext!!
                    )

                    // Verify
                    val verification = specialistRuntime.verifyOutcome(outcome, intent, specialistRuntime.lastContext)
                    verdict = if (verification.passed) SpecialistVerdict.VERIFIED else SpecialistVerdict.UNVERIFIED
                    result = outcome.notes.joinToString("; ")
                    evidence += outcome.notes
                    toolCalls = outcome.completed.size + outcome.failed.size
                }
            }
        } catch (e: Exception) {
            result = if (e is kotlinx.coroutines.TimeoutCancellationException) {
                "error: specialist timed out after ${TIMEOUT_MS / 60000} minutes"
            } else {
                "error: specialist execution failed: ${e.javaClass.simpleName}: ${e.message}"
            }
            verdict = SpecialistVerdict.FAILED
        } finally {
            val completedAt = System.currentTimeMillis()
            synchronized(sessionLock) {
                session.status = if (verdict == SpecialistVerdict.FAILED) SpecialistStatus.FAILED else SpecialistStatus.COMPLETED
                session.result = result
                session.evidence = evidence
                session.verdict = verdict
                session.toolCalls = toolCalls
                session.completedAt = completedAt
            }
            // Clean up broker session state
            capabilityBroker.revokeSessionCapabilities(session.sessionId)
        }
    }

    private fun buildSpecialistPrompt(session: SpecialistSession): String {
        val sb = StringBuilder()
        sb.append("You are a ${session.specialism.description.toLowerCase()} specialist. ")
        sb.append("Your parent mission (${session.parentMissionId}) assigned you: ${session.objective}\n\n")
        sb.append("Initial tools: ${session.allowedTools.joinToString(", ")}\n")
        sb.append("DYNAMIC CAPABILITY EXPANSION: You can request additional tools at any time by ")
        sb.append("calling the 'request_capabilities' tool with the tool names you need and a reason. ")
        sb.append("The Capability Broker will discover, authorize, and provision available capabilities ")
        sb.append("without requiring user interaction for ordinary capabilities.\n")
        sb.append("Constraints:\n")
        sb.append("- Start with your initial tools; request more when needed via 'request_capabilities'.\n")
        sb.append("- You are a subordinate agent. Report findings to your parent, do not act independently.\n")
        sb.append("- Maximum ${MAX_SESSION_DURATION_MS / 60000} minutes. Be concise.\n")
        sb.append("- Return evidence for every claim. Verification is required.\n")
        sb.append("- When done, output your final answer naturally. Do not use tool format.\n")
        return sb.toString()
    }

    private fun executeSpecialistTools(
        session: SpecialistSession,
        specialistNim: Nim,
        actionResponse: String
    ): String {
        // Simplified tool execution for specialist
        // In real implementation, this would parse ACTION format and dispatch
        return "Specialist completed with available tools: ${session.allowedTools.joinToString(", ")}"
    }

    companion object {
        private val missionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private fun msg(role: String, content: String): org.json.JSONObject = org.json.JSONObject().put("role", role).put("content", content)
    }
}

/**
 * Specialist session state.
 */
internal data class SpecialistSession(
    val sessionId: String,
    val parentMissionId: String,
    val specialism: OrionSpecialism,
    val objective: String,
    val allowedTools: Set<String>,
    val contextSnapshot: OrionContextIntegration.UnifiedContext,
    val createdAt: Long,
    var status: SpecialistStatus,
    var result: String = "",
    var evidence: List<String> = emptyList(),
    var verdict: SpecialistVerdict = SpecialistVerdict.FAILED,
    var toolCalls: Int = 0,
    var completedAt: Long = 0L
) {
    fun cancel(reason: String) {
        status = SpecialistStatus.CANCELLED
        result = "Cancelled: $reason"
        verdict = SpecialistVerdict.CANCELLED
    }

    fun buildReceipt(): OrionSpecialistReceipt = OrionSpecialistReceipt(
        sessionId = sessionId,
        parentMissionId = parentMissionId,
        specialism = specialism,
        objective = objective,
        result = result,
        evidence = evidence,
        verdict = verdict,
        startedAt = createdAt,
        completedAt = completedAt.ifZero { createdAt },
        toolCalls = toolCalls
    )
}

internal enum class SpecialistStatus { STARTING, RUNNING, COMPLETED, FAILED, CANCELLED }
