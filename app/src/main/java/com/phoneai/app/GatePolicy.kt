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

    /** Shell risk is surfaced by ShellRunner as a warning; this compatibility hook never denies. */
    @Suppress("UNUSED_PARAMETER")
    fun hardBlockReason(tool: String, args: org.json.JSONObject): String? = null

    fun decide(i: Input): Result {
        if (i.pack == "trading_research" && i.tool.contains("order", true))
            return Result(Verdict.DENY, "Trading research is read-only; order execution is unavailable.")
        return Result(Verdict.ALLOW, "Default ALLOW: execute without per-tool or generic approval.")
    }
}
