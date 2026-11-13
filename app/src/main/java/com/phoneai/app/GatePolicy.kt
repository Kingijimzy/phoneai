package com.phoneai.app

internal object GatePolicy {
    enum class Verdict { ALLOW, DENY, PLAN_ONLY }
    data class Input(val pack: String, val packOn: Boolean, val tool: String, val mode: AgentPolicy.Mode, val toolMode: ToolMode, val autonomous: Boolean, val dangerous: Boolean, val approvedThisAction: Boolean, val trustedForPack: Boolean, val allowlisted: Boolean, val askedThisTask: Boolean, val hardBlockReason: String? = null)
    data class Result(val verdict: Verdict, val reason: String)
    @Suppress("UNUSED_PARAMETER") fun hardBlockReason(tool: String, args: org.json.JSONObject): String? = null
    fun decide(i: Input): Result {
        if (i.hardBlockReason != null) return Result(Verdict.DENY, i.hardBlockReason)
        if (i.pack.isBlank() || !i.packOn) return Result(Verdict.DENY, "The '${i.pack}' capability pack is disabled or unavailable. Enable it in Orion's capability settings.")
        if (i.mode == AgentPolicy.Mode.PLAN) return Result(Verdict.PLAN_ONLY, "Plan mode is active. Orion can plan this action but cannot execute it.")
        if (i.toolMode == ToolMode.DENY) return Result(Verdict.DENY, "The owner has denied the '${i.tool}' tool in Orion's tool permissions.")
        if (i.pack == "trading_research" && i.tool.contains("order", true)) return Result(Verdict.DENY, "Trading research is read-only; order execution is unavailable.")
        val trusted = i.approvedThisAction || i.trustedForPack
        if (i.toolMode == ToolMode.ASK && !trusted) return Result(Verdict.DENY, "The '${i.tool}' tool requires owner approval before execution.")
        if (i.dangerous && !trusted) return Result(Verdict.DENY, "This consequential action requires explicit approval or an active trusted session.")
        return Result(Verdict.ALLOW, if (i.toolMode == ToolMode.ASK || i.dangerous) "Allowed by explicit owner approval." else "Allowed by Orion policy; Android OS permissions are still required.")
    }
}
