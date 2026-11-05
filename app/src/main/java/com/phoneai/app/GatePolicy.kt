package com.phoneai.app

/**
 * Dispatch policy: capability availability, explicit owner DENY, and PLAN mode only.
 * ASK/ALLOWLIST and generic consequential-action approvals are intentionally not agent gates.
 */
internal object GatePolicy {
    enum class Verdict { ALLOW, DENY, PLAN_ONLY }
    data class Input(
        val pack: String, val packOn: Boolean, val tool: String,
        val mode: AgentPolicy.Mode, val toolMode: ToolMode, val autonomous: Boolean,
        val dangerous: Boolean, val approvedThisAction: Boolean,
        val trustedForPack: Boolean, val allowlisted: Boolean, val askedThisTask: Boolean,
        val hardBlockReason: String? = null
    )
    data class Result(val verdict: Verdict, val reason: String)

    /** No private-data, remote-pipe, root, block-device, or fork-bomb shell policy. */
    fun hardBlockReason(tool: String, args: org.json.JSONObject): String? = null

    fun decide(i: Input): Result {
        if (!i.packOn) return Result(Verdict.DENY, AgentPolicy.packOffMessage(i.pack))
        if (i.pack == "trading_research" && i.tool.contains("order", true))
            return Result(Verdict.DENY, "Trading research is read-only; order execution is unavailable.")
        if (i.toolMode == ToolMode.DENY)
            return Result(Verdict.DENY, ToolPermissionPolicy.denyMessage(i.tool))
        if (i.mode == AgentPolicy.Mode.PLAN)
            return Result(Verdict.PLAN_ONLY, "Plan mode never executes tools.")
        return Result(Verdict.ALLOW, "Default ALLOW: execute without per-tool or generic approval.")
    }
}
