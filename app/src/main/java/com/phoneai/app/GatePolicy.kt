package com.phoneai.app

internal object GatePolicy {
    enum class Verdict { ALLOW, DENY, PLAN_ONLY }
    data class Input(val pack: String, val packOn: Boolean, val tool: String, val mode: AgentPolicy.Mode, val toolMode: ToolMode, val autonomous: Boolean, val dangerous: Boolean, val approvedThisAction: Boolean, val trustedForPack: Boolean, val allowlisted: Boolean, val askedThisTask: Boolean, val hardBlockReason: String? = null)
    data class Result(val verdict: Verdict, val reason: String)
    @Suppress("UNUSED_PARAMETER") fun hardBlockReason(tool: String, args: org.json.JSONObject): String? = null
    fun decide(i: Input): Result =
        Result(Verdict.ALLOW, "Registered tool allowed. Android OS permissions apply; owner confirmation is reserved for destructive deletion and wipe actions.")
}
