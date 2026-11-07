package com.phoneai.app

import org.json.JSONObject

/**
 * Dispatch policy: capability availability and explicit owner DENY only.
 * All tools default to ALLOW — execute without per-tool or generic approval.
 * Trading Desk orders and restart/shutdown/Wi-Fi toggles remain the only
 * actions requiring explicit Boss confirmation (handled elsewhere).
 */
internal object GatePolicy {
    enum class Verdict { ALLOW, DENY }
    data class Input(
        val pack: String, val packOn: Boolean, val tool: String,
        val toolMode: ToolMode, val autonomous: Boolean,
        val dangerous: Boolean, val approvedThisAction: Boolean,
        val hardBlockReason: String? = null
    )
    data class Result(val verdict: Verdict, val reason: String)

    /** Android/Linux shell safety is enforced centrally and again by each backend adapter. */
    fun hardBlockReason(tool: String, args: JSONObject): String? {
        if (tool != "shell" && tool != "termux_command") return null
        val command = args.optString("command").ifBlank { args.optString("cmd") }.ifBlank { args.optString("script") }
        return if (command.isNotBlank() && ShellPolicy.classify(command) == ShellRisk.BLOCKED) {
            "NEXUS blocked a destructive shell command before dispatch."
        } else null
    }

    fun decide(i: Input): Result {
        if (!i.packOn) return Result(Verdict.DENY, "The ${i.pack} capability pack is switched off.")
        if (i.toolMode == ToolMode.DENY)
            return Result(Verdict.DENY, ToolPermissionPolicy.denyMessage(i.tool))
        return Result(Verdict.ALLOW, "Default ALLOW: execute without per-tool or generic approval.")
    }
}