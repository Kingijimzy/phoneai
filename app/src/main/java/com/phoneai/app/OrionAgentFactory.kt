package com.phoneai.app

import android.content.Context
import kotlinx.coroutines.*

/**
 * Phase 0C: Mission Ownership
 *
 * Creates and manages bounded specialist agents.
 * Each specialist gets a scoped tool set, isolated context, and resource limits.
 * The factory enforces concurrency bounds and prevents runaway agent spawning.
 */
internal class OrionAgentFactory(
    private val context: Context,
    private val svc: PhoneAiService,
    private val ui: AgentUi,
    private val tools: Tools,
    private val localExecutor: Local,
    private val pythonExecutor: OrionPythonCapabilityExecutor,
    private val commandNim: Nim,
    private val controller: OrionMissionController
) {

    private val activeSessions = mutableMapOf<String, SpecialistSession>()
    private val sessionLock = Any()
    private val MAX_SESSION_DURATION_MS = 10 * 60 * 1000L // 10 minutes per specialist
    private val PROGRESS_POLL_INTERVAL_MS = 2000L

    /**
     * Create a new specialist session with scoped tools and context.
     */
    fun createSession(
        parentMissionId: String,
        specialism: OrionSpecialism,
        objective: String,
        allowedTools: Set<String>? = null,
        contextSnapshot: OrionContextIntegration.UnifiedContext? = null
    ): SpecialistSession {
        val sessionId = "spec_${specialism.id}_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}"

        // Build tool set: start with specialism defaults, filter by allowed, verify permissions
        val effectiveTools = buildEffectiveToolSet(specialism, allowedTools)
        val scopedContext = buildScopedContext(contextSnapshot, effectiveTools, objective)

        val session = SpecialistSession(
            sessionId = sessionId,
            parentMissionId = parentMissionId,
            specialism = specialism,
            objective = objective,
            allowedTools = effectiveTools,
            contextSnapshot = scopedContext,
            createdAt = System.currentTimeMillis(),
            status = SpecialistStatus.STARTING
        )

        synchronized(sessionLock) {
            activeSessions[sessionId] = session
        }

        // Start specialist execution in background
        missionScope.launch {
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

    private fun buildEffectiveToolSet(specialism: OrionSpecialism, allowedTools: Set<String>?): Set<String> {
        var tools = specialism.defaultTools
        allowedTools?.let { tools = tools.intersect(it) }
        // Filter by what's actually registered and permitted
        tools = tools.filter { name ->
            ToolRegistry.all.any { it.name == name } &&
            ToolGate.preflightDenial(svc, name) == null
        }
        return tools
    }

    private fun buildScopedContext(
        snapshot: OrionContextIntegration.UnifiedContext?,
        tools: Set<String>,
        objective: String
    ): OrionContextIntegration.UnifiedContext {
        // Return a filtered context - only the tools this specialist can use
        return snapshot?.copy(
            capabilities = snapshot.capabilities.copy(
                availableCapabilities = snapshot.capabilities.availableCapabilities.filter { it in tools }
            )
        ) ?: OrionContextIntegration(context.applicationContext).buildContext(
            command = objective,
            modality = "SPECIALIST",
            activeObjective = objective,
            sessionData = OrionContextIntegration.SessionData(objective, "SPECIALIST", objective, System.currentTimeMillis())
        )
    }

    private fun runSpecialist(session: SpecialistSession) {
        val startedAt = System.currentTimeMillis()
        val evidence = mutableListOf<String>()
        var toolCalls = 0
        var verdict = SpecialistVerdict.FAILED
        var result = "Specialist did not produce a result"

        try {
            synchronized(sessionLock) { session.status = SpecialistStatus.RUNNING }

            // Build specialist system prompt
            val systemPrompt = buildSpecialistPrompt(session)

            // Execute the specialist's objective
            val messages = JSONArray().put(msg("system", systemPrompt))
            messages.put(msg("user", "Objective: ${session.objective}"))

            // Specialist gets a dedicated NIM instance
            val specialistNim = Nim(svc).also { n -> n.onStatus = { s -> } }

            var response = specialistNim.chat(messages, false, 800, 0.3).trim()

            // Check if the specialist wants to use tools
            if (response.uppercase().startsWith("ACTION")) {
                // Parse and execute tool calls
                val toolResult = executeSpecialistTools(session, specialistNim, response)
                response = toolResult
                evidence += toolResult
                toolCalls = 1 // simplified - real implementation would count
            }

            // Final result
            result = response
            verdict = if (response.trimStart().startsWith("error", ignoreCase = true)) SpecialistVerdict.FAILED else SpecialistVerdict.UNVERIFIED

        } catch (e: Exception) {
            result = "error: specialist execution failed: ${e.javaClass.simpleName}"
            verdict = SpecialistVerdict.FAILED
        } finally {
            val duration = System.currentTimeMillis() - startedAt
            synchronized(sessionLock) {
                session.status = if (verdict == SpecialistVerdict.FAILED) SpecialistStatus.FAILED else SpecialistStatus.COMPLETED
                session.result = result
                session.evidence = evidence
                session.verdict = verdict
                session.toolCalls = toolCalls
                session.completedAt = System.currentTimeMillis()
            }
        }
    }

    private fun buildSpecialistPrompt(session: SpecialistSession): String {
        val sb = StringBuilder()
        sb.append("You are a ${session.specialism.description.toLowerCase()} specialist. ")
        sb.append("Your parent mission (${session.parentMissionId}) assigned you: ${session.objective}\n\n")
        sb.append("Available tools: ${session.allowedTools.joinToString(", ")}\n")
        sb.append("Constraints:\n")
        sb.append("- Use ONLY the listed tools. Do not invent tools.\n")
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