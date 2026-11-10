package com.phoneai.app

/**
 * Dispatch policy: enabled capability, per-tool ALLOW / ASK / DENY, PLAN mode,
 * and deterministic hard blocks. The Agent owns the one user-facing approval prompt.
 */
internal object GatePolicy {
    enum class Verdict { ALLOW, ASK, DENY, PLAN_ONLY }
    data class Input(
        val pack: String, val packOn: Boolean, val tool: String,
        val mode: AgentPolicy.Mode, val toolMode: ToolMode, val autonomous: Boolean,
        val dangerous: Boolean, val approvedThisAction: Boolean,
        val trustedForPack: Boolean, val allowlisted: Boolean, val askedThisTask: Boolean,
        val hardBlockReason: String? = null
    )
    data class Result(val verdict: Verdict, val reason: String)

    /** Android/Linux shell safety is enforced centrally and again by each backend adapter. */
    fun hardBlockReason(tool: String, args: org.json.JSONObject): String? {
        if (tool != "shell" && tool != "termux_command") return null
        val command = args.optString("command").ifBlank { args.optString("cmd") }.ifBlank { args.optString("script") }
        return if (command.isNotBlank() && ShellPolicy.classify(command) == ShellRisk.BLOCKED) {
            "Orion blocked a destructive Android/Linux shell command before dispatch."
        } else null
    }

    fun decide(i: Input): Result {
        if (!i.hardBlockReason.isNullOrBlank())
            return Result(Verdict.DENY, i.hardBlockReason)
        if (i.pack == "trading_research" && i.tool.contains("order", true))
            return Result(Verdict.DENY, "Trading and coach order controls remain active.")
        return Result(Verdict.ALLOW, "Partner operation: no generic app-level permission prompt.")
    }
}
