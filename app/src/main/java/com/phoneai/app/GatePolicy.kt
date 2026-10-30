package com.phoneai.app

/**
 * The single decision table for whether a tool may run now. Pure logic, no Android, so every row is unit tested.
 * Order matters and is the same for the agent and for every other caller:
 *  1. capability pack switched off, or research-pack order attempt -> DENY
 *  2. user set the tool to DENY                                   -> DENY
 *  3. plan mode                                                   -> PLAN_ONLY
 *  4. the user already approved this exact action                 -> ALLOW
 *  5. tool set to ASK, not yet asked this task, not autonomous    -> ASK_TOOL (once per task)
 *  6. consequential action not covered by a timed trust           -> NEED_APPROVAL
 *  7. autonomous mode and a routine step                          -> ALLOW
 *  8. the global approval mode decides (ASK / ALLOWLIST / TRUSTED)
 *
 * Trading: order tools belong on trading_execution. trading_research is read-only even if a tool name slips through.
 */
internal object GatePolicy {
    enum class Verdict { ALLOW, DENY, PLAN_ONLY, ASK_TOOL, NEED_APPROVAL }

    data class Input(
        val pack: String,
        val packOn: Boolean,
        val tool: String,
        val mode: AgentPolicy.Mode,
        val toolMode: ToolMode,
        val autonomous: Boolean,
        val dangerous: Boolean,
        val approvedThisAction: Boolean,
        val trustedForPack: Boolean,
        val allowlisted: Boolean,
        val askedThisTask: Boolean
    )

    data class Result(val verdict: Verdict, val reason: String)

    /** Order / cancel / leverage style tools — never research-only. */
    fun isTradingOrderTool(tool: String): Boolean {
        val t = tool.lowercase()
        return t.contains("order") ||
            t.contains("place_order") ||
            t.endsWith("_leverage") ||
            t.contains("set_leverage") ||
            t.contains("cancel_order") ||
            t.contains("close_position")
    }

    fun decide(i: Input): Result {
        if (!i.packOn) return Result(Verdict.DENY, AgentPolicy.packOffMessage(i.pack))
        // Safety net: research pack never executes orders even if mis-mapped.
        if (i.pack == "trading_research" && isTradingOrderTool(i.tool))
            return Result(
                Verdict.DENY,
                "Trading research is read-only. Enable the Trading execution pack in Agent control and approve each order."
            )
        // Execution pack off is already DENY via packOn; when on, orders remain consequential.
        if (i.pack == "trading_execution" && isTradingOrderTool(i.tool) && !i.approvedThisAction && !i.trustedForPack)
            return Result(
                Verdict.NEED_APPROVAL,
                "Order execution needs your explicit approval or a Trusted session covering Trading execution."
            )
        if (i.toolMode == ToolMode.DENY) return Result(Verdict.DENY, ToolPermissionPolicy.denyMessage(i.tool))
        if (i.mode == AgentPolicy.Mode.PLAN) return Result(Verdict.PLAN_ONLY, "Plan mode never executes tools.")
        if (i.approvedThisAction) return Result(Verdict.ALLOW, "User approved this action.")
        if (i.toolMode == ToolMode.ASK && !i.autonomous && !i.askedThisTask)
            return Result(Verdict.ASK_TOOL, "The user set '${i.tool}' to ask each task.")
        if (i.dangerous && !i.trustedForPack) return Result(Verdict.NEED_APPROVAL, "Consequential action needs clear confirmation.")
        // The per-tool setting is the user's explicit authorization. Once a tool is ALLOW,
        // do not make the user approve it again through the unrelated global ASK/ALLOWLIST
        // mode. A tool set to ASK asks once per task; consequential actions still require
        // the dedicated confirmation above unless covered by a trusted session.
        if (i.toolMode == ToolMode.ALLOW) return Result(Verdict.ALLOW, "Tool is enabled in Tool permissions.")
        if (i.toolMode == ToolMode.ASK && i.askedThisTask) return Result(Verdict.ALLOW, "Tool was approved for this task.")
        // Autonomous mode only relaxes routine tool prompts; it never bypasses consequential-action gates.
        if (i.autonomous && !i.dangerous) return Result(Verdict.ALLOW, "Autonomous mode: routine task step allowed.")
        return when (i.mode) {
            AgentPolicy.Mode.PLAN -> Result(Verdict.PLAN_ONLY, "Plan mode never executes tools.")
            AgentPolicy.Mode.ASK -> Result(Verdict.NEED_APPROVAL, "Ask mode requires approval before each action.")
            AgentPolicy.Mode.ALLOWLIST ->
                if (i.allowlisted) Result(Verdict.ALLOW, "Tool is allowlisted.") else Result(Verdict.NEED_APPROVAL, "Tool is not allowlisted.")
            AgentPolicy.Mode.TRUSTED ->
                if (i.trustedForPack) Result(Verdict.ALLOW, "Timed trust covers this enabled pack.")
                else Result(Verdict.NEED_APPROVAL, "This pack is not covered by the timed trust session.")
        }
    }
}
