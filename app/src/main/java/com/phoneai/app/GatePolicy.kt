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

    /** Android/Linux shell safety is enforced centrally and again by each backend adapter. */
    fun hardBlockReason(tool: String, args: org.json.JSONObject): String? {
        if (tool != "shell" && tool != "termux_command") return null
        val command = args.optString("command").ifBlank { args.optString("cmd") }.ifBlank { args.optString("script") }
        if (command.isBlank()) return null
        // Deleting or wiping is the one thing Orion confirms with Kingi: once he says yes in chat, the call is repeated with confirmed=true.
        if (args.optBoolean("confirmed")) { ShellPolicy.confirm(command); return null }
        return if (ShellPolicy.classify(command) == ShellRisk.BLOCKED) {
            "This command deletes or wipes something important. Ask Kingi in chat to confirm it; when he says yes, call the same tool again with confirmed=true."
        } else null
    }

    fun decide(i: Input): Result {
        if (i.pack == "trading_research" && i.tool.contains("order", true))
            return Result(Verdict.DENY, "Trading research is read-only; order execution is unavailable.")
        return Result(Verdict.ALLOW, "Default ALLOW: execute without per-tool or generic approval.")
    }
}


/** A narrow confirmation warning for only the three irreversible computer settings actions. */
internal object IrreversibleActionConfirmationPolicy {
    private val computerAction = Regex("""(?i)computer_settings[^}]{0,180}(restart|shutdown|(?:wifi|wi[- ]?fi)(?:[_ -]?toggle)?|toggle[_ -]?(?:wifi|wi[- ]?fi)|turn\s+(?:wifi|wi[- ]?fi)\s+(?:on|off))""")
    fun requiresConfirmation(description: String): Boolean =
        computerAction.containsMatchIn(description) &&
            !Regex("""(?i)computer_settings[^}]{0,180}(restart|shutdown|wifi(?:[_ -]?toggle)?|toggle[_ -]?wifi|turn\s+wifi\s+(?:on|off))""")
                .containsMatchIn(description.substringBefore("settings screen"))
}
