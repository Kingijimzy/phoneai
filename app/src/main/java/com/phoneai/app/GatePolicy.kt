package com.phoneai.app

/**
 * The single decision table for whether a tool may run now. Pure logic, no Android, so every row is unit tested.
 * Order matters and is the same for the agent and for every other caller:
 *  1. capability pack switched off, or a forbidden trading order  -> DENY
 *  2. user set the tool to DENY                                   -> DENY
 *  3. plan mode                                                   -> PLAN_ONLY
 *  4. the user already approved this exact action                 -> ALLOW
 *  5. tool set to ASK, not yet asked this task, not autonomous    -> ASK_TOOL (once per task)
 *  6. consequential action not covered by a timed trust           -> NEED_APPROVAL
 *  7. autonomous mode and a routine step                          -> ALLOW
 *  8. the global approval mode decides (ASK / ALLOWLIST / TRUSTED)
 */
internal object GatePolicy {
    enum class Verdict { ALLOW, DENY, PLAN_ONLY, ASK_TOOL, NEED_APPROVAL }
    data class Input(
        val pack: String, val packOn: Boolean, val tool: String,
        val mode: AgentPolicy.Mode, val toolMode: ToolMode, val autonomous: Boolean,
        val dangerous: Boolean, val approvedThisAction: Boolean,
        val trustedForPack: Boolean, val allowlisted: Boolean, val askedThisTask: Boolean,
        val hardBlockReason: String? = null
    )
    data class Result(val verdict: Verdict, val reason: String)

    /** Non-overridable hard blocks. No saved grant or approval response can change these results. */
    fun hardBlockReason(tool: String, args: org.json.JSONObject): String? {
        val raw = args.toString()
        val command = when (tool) {
            "shell", "termux_command" -> args.optString("command")
            else -> ""
        }
        if (command.isNotBlank() && ShellPolicy.classify(command) == ShellRisk.BLOCKED)
            return "Hard block: root/system wipe, raw block-device write, or fork bomb."
        val forms = listOf(raw, raw.replace("\"", "").replace("'", "").replace("\\", ""))
        if (forms.any { Regex("""(?is)\b(?:curl|wget)\b[^;&|]*\|\s*(?:sudo\s+)?(?:sh|bash|zsh)\b""").containsMatchIn(it) })
            return "Hard block: piping remote internet content directly into a shell is forbidden."
        if (tool == "vault_get" || forms.any {
                Regex("""(?i)(?:ApiKeyVault|api[_-]?key[_-]?vault|shared_prefs|AndroidKeyStore|/keystore/|/data/(?:user/0|data)/com\.phoneai\.app/(?:shared_prefs|files/.+vault))""").containsMatchIn(it)
            })
            return "Hard block: Orion's own keys, private preferences, and keystore material are not readable by agent tools."
        if (tool in setOf("python_exec", "shell", "termux_command") &&
            Regex("""(?is)(?:/dev/block/|/dev/(?:mmcblk\d+|sd[a-z]|nvme\d+n\d+|dm-\d+)).{0,160}(?:write|wb|truncate|dd)|(?:write|wb|truncate|dd).{0,160}/dev/(?:block/|mmcblk\d+|sd[a-z]|nvme\d+n\d+|dm-\d+)""").containsMatchIn(raw))
            return "Hard block: writing raw block devices is forbidden."
        return null
    }

    /** Only decision point: hard block → pack → DENY → PLAN → exact approval → explicit tool ASK → non-undoable approval → ALLOW. */
    fun decide(i: Input): Result {
        i.hardBlockReason?.let { return Result(Verdict.DENY, it) }
        if (!i.packOn) return Result(Verdict.DENY, AgentPolicy.packOffMessage(i.pack))
        if (i.pack == "trading_research" && i.tool.contains("order", true))
            return Result(Verdict.DENY, "Trading research is read-only; order execution is unavailable.")
        if (i.toolMode == ToolMode.DENY) return Result(Verdict.DENY, ToolPermissionPolicy.denyMessage(i.tool))
        if (i.mode == AgentPolicy.Mode.PLAN) return Result(Verdict.PLAN_ONLY, "Plan mode never executes tools.")
        if (i.approvedThisAction) return Result(Verdict.ALLOW, "Owner approved this exact action.")
        if (i.toolMode == ToolMode.ASK && !i.askedThisTask && !i.autonomous)
            return Result(Verdict.ASK_TOOL, "An explicit per-tool ASK setting requires approval.")
        if (i.dangerous) return Result(Verdict.NEED_APPROVAL, "This action is not safely reversible and needs one-time owner approval.")
        return Result(Verdict.ALLOW, if (i.autonomous) "Autonomous execution: routine action allowed." else "Default ALLOW: routine action allowed.")
    }
}
