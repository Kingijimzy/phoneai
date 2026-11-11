package com.phoneai.app

/**
 * GRASP is the executive reasoning cycle, not a list of user commands:
 * Ground -> Retrieve -> Assess -> Select -> Perform.
 *
 * The Central Brain supplies live state and evidence to each phase. The model may interpret
 * natural language and propose a route, but registered executors, capability policy, approvals,
 * checkpoints and evidence verification remain code-enforced boundaries.
 */
internal object OrionGraspProtocol {
    enum class Phase { GROUND, RETRIEVE, ASSESS, SELECT, PERFORM }

    val orderedPhases: List<Phase> = listOf(
        Phase.GROUND, Phase.RETRIEVE, Phase.ASSESS, Phase.SELECT, Phase.PERFORM
    )

    fun contextBlock(): String = """
ORION GRASP EXECUTIVE CYCLE (runtime-grounded; do not treat user language as a command syntax):
G — GROUND: infer the user's intended outcome from the full current message, recent dialogue, active objective and corrections. Preserve conversational intent; do not trigger a tool merely because a capability word appears.
R — RETRIEVE: load relevant MMAG layers, durable task/checkpoint state, prior decisions and verified experience. Keep source, age, confidence and provenance; current user statements outrank inferred preferences and old assistant claims.
A — ASSESS: compare the intended outcome with live capability/connection/permission state, diagnostics, uncertainty, conflicts, risk and remaining budget. If evidence is insufficient or references conflict, ask one focused question rather than inventing.
S — SELECT: choose the smallest reliable route from actually registered and enabled capabilities. Prefer a verified working alternative after failure. The model proposes; the app's dispatch and safety policies decide what may execute.
P — PERFORM: execute one bounded step, observe its actual result, verify the requested postcondition, persist a sanitized receipt and update the objective. Then repeat G-R-A-S-P using the new observation until verified complete, genuinely blocked, cancelled or owner input is required.
Never equate a plan with an invocation, an invocation with success, or a model-generated claim with evidence. Never replay an uncertain in-flight action before reconciling its outcome.
""".trimIndent()
}
