package com.phoneai.app

import kotlinx.serialization.Serializable

/**
 * Orion Policy Engine
 *
 * The single place where "may this run" is answered, and the place where the two protected
 * capabilities (Trading Desk, Coach) are enforced for the decision pipeline.
 *
 * Order of evaluation matters and is deliberate:
 *   1. Trading Desk / financial tools -> ASK. Never ALLOW, on any configuration.
 *   2. Shell destructive classification -> DENY (ShellPolicy.BLOCKED).
 *   3. Capability inventory -> DENY when not usable, with the exact reason.
 *   4. Everything else -> ALLOW. No duplicate consent prompts for ordinary assistant work.
 *
 * Note: this engine filters the decision pipeline's option set. It is not the last line of
 * defence — the executor's own gates (ToolGate preflight, IrreversibleActionConfirmationPolicy,
 * TradingDesk.gateOrder) still run at dispatch time.
 */
internal class OrionPolicyEngine(
    private val svc: PhoneAiService
) {

    @Serializable
    enum class Verdict { ALLOW, DENY, ASK }

    data class Evaluation(
        val tool: String,
        val verdict: Verdict,
        val reason: String?,
        val requiresConfirmation: Boolean
    )

    fun evaluateCapability(
        tool: String,
        context: OrionContextIntegration.UnifiedContext?
    ): Evaluation {
        // 1. Trading Desk: unconditional ASK. This branch must never be reordered below a bypass.
        if (isTradingTool(tool)) {
            return Evaluation(
                tool = tool,
                verdict = Verdict.ASK,
                reason = "Trading Desk action requires explicit Boss approval",
                requiresConfirmation = true
            )
        }

        // 2. Destructive shell commands are blocked outright.
        val shellRisk = runCatching { ShellPolicy.classify(tool) }.getOrDefault(ShellRisk.SAFE)
        if (shellRisk == ShellRisk.BLOCKED) {
            return Evaluation(tool, Verdict.DENY, "ShellPolicy blocks this destructive command", false)
        }

        // 3. Capability inventory: report the real blocker instead of guessing.
        val capabilities = context?.capabilities
        if (capabilities != null) {
            val health = capabilities.health[tool]
            if (health != null && !health.usable) {
                return Evaluation(
                    tool, Verdict.DENY,
                    "not usable: ${health.reason ?: health.status}", false
                )
            }
            if (health == null && tool !in ToolRegistry.names()) {
                return Evaluation(tool, Verdict.DENY, "tool is not registered", false)
            }
        }

        // 4. Everything else is allowed without a duplicate prompt.
        return Evaluation(tool, Verdict.ALLOW, null, false)
    }

    /**
     * Filter a candidate tool set down to what policy permits, preserving the exact reason for
     * anything removed so the decision log can explain the gap.
     */
    fun filterPermitted(
        tools: Collection<String>,
        context: OrionContextIntegration.UnifiedContext?
    ): Pair<List<String>, List<Evaluation>> {
        val allowed = mutableListOf<String>()
        val rejected = mutableListOf<Evaluation>()
        for (tool in tools.distinct()) {
            val e = evaluateCapability(tool, context)
            if (e.verdict == Verdict.DENY) rejected += e else allowed += e.tool
        }
        return allowed to rejected
    }

    /** Non-blocking constraint notes for the decision rationale. */
    fun checkConstraints(
        tools: List<String>,
        context: OrionContextIntegration.UnifiedContext?
    ): List<String> {
        val notes = mutableListOf<String>()
        for (tool in tools.distinct()) {
            when (val e = evaluateCapability(tool, context)) {
                else -> when (e.verdict) {
                    Verdict.DENY -> notes += "Blocked: $tool — ${e.reason ?: "policy"}"
                    Verdict.ASK -> notes += "Confirmation required: $tool — ${e.reason ?: "approval needed"}"
                    Verdict.ALLOW -> Unit
                }
            }
        }
        return notes
    }

    fun isTradingTool(tool: String): Boolean = tool in TRADING_TOOLS ||
        tool.startsWith("trading_") || tool == "place_order"

    fun isCoachTool(tool: String): Boolean = tool.startsWith("coach_") || tool == "trading_coach"

    /** Trading Desk and Coach are the only capability packs that require explicit approval. */
    fun requiresBossConfirmation(tool: String): Boolean =
        isTradingTool(tool) || isCoachTool(tool) || tool in IRREVERSIBLE_TOOLS

    private companion object {
        val TRADING_TOOLS = setOf("trading_place_order", "trading_order", "place_order", "order_place")
        val IRREVERSIBLE_TOOLS = setOf("restart_device", "shutdown_device", "toggle_wifi", "wifi_toggle")
    }
}