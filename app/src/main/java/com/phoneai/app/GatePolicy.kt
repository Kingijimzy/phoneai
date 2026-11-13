package com.phoneai.app

internal object GatePolicy {
    enum class Verdict { ALLOW, DENY, PLAN_ONLY }
    data class Input(val pack: String, val packOn: Boolean, val tool: String, val mode: AgentPolicy.Mode, val toolMode: ToolMode, val autonomous: Boolean, val dangerous: Boolean, val approvedThisAction: Boolean, val trustedForPack: Boolean, val allowlisted: Boolean, val askedThisTask: Boolean, val hardBlockReason: String? = null)
    data class Result(val verdict: Verdict, val reason: String)
    @Suppress("UNUSED_PARAMETER") fun hardBlockReason(tool: String, args: org.json.JSONObject): String? = null
    fun decide(i: Input): Result {
        // Orion-level permission toggles and capability-pack switches are advisory only.
        // Registered tools run directly; Android runtime/special permissions are enforced by
        // the concrete Android executor. Destructive data operations use the separate,
        // per-operation confirmation policy in Agent.kt.
        if (i.hardBlockReason != null) return Result(Verdict.DENY, i.hardBlockReason)
        return Result(Verdict.ALLOW, "Registered tool allowed. Android OS permissions and destructive-operation confirmation still apply.")
    }
}
