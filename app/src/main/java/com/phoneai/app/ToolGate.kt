package com.phoneai.app

import android.content.Context

/**
 * Tool status reporting. No preflight denials — all tools default to ALLOW.
 * Trading Desk orders and restart/shutdown/Wi-Fi toggles require explicit Boss confirmation
 * (handled in Agent.kt dispatch).
 */
internal object ToolGate {

    /** Returns empty string when everything is ready; otherwise lists what's off. */
    fun summarize(
        tools: List<String>,
        modeOf: (String) -> ToolMode,
        packOf: (String) -> String?,
        packOn: (String) -> Boolean,
        titleOf: (String) -> String
    ): String {
        val offByPack = LinkedHashMap<String, MutableList<String>>()
        val denied = ArrayList<String>()
        for (tool in tools) {
            if (tool in ToolPermissionPolicy.alwaysAllowed) continue
            if (modeOf(tool) == ToolMode.DENY) { denied += tool; continue }
            val pack = packOf(tool) ?: continue
            if (!packOn(pack)) offByPack.getOrPut(pack) { ArrayList() } += tool
        }
        if (offByPack.isEmpty() && denied.isEmpty()) return ""
        val sb = StringBuilder("TOOL STATUS RIGHT NOW:\n")
        offByPack.forEach { (pack, list) ->
            sb.append("- ").append(titleOf(pack)).append(" is OFF, so these are unavailable: ")
                .append(list.take(6).joinToString(", ")).append(if (list.size > 6) " and ${list.size - 6} more" else "")
                .append(". Switch it on in ").append(howToEnable(pack)).append(".\n")
        }
        if (denied.isNotEmpty()) {
            sb.append("- The Boss has set these to DENY: ").append(denied.take(8).joinToString(", "))
                .append(". Change this in Tool permissions.\n")
        }
        sb.append("- Everything else is ready.\n")
        return sb.toString()
    }

    /** No preflight denial — tools default to ALLOW. Returns null always. */
    fun preflightDenial(c: Context, tool: String): String? = null

    /** Where the user switches a pack on. */
    fun howToEnable(pack: String): String = "Agent control > Packs"

    /** The live status for the system prompt. */
    fun statusFor(c: Context): String {
        val rows = ToolRegistry.all.map { it.name }
            .filterNot { it in ToolPermissionPolicy.alwaysAllowed }
            .map { tool ->
                val denied = ToolPermissions.mode(c, tool) == ToolMode.DENY
                val pack = AgentPolicy.packForTool(tool)
                val packOn = pack == null || AgentPolicy.enabled(c, pack)
                CapabilityRegistry.Row(tool, pack, denied, CapabilityRegistry.forBuiltInTool(tool, denied, packOn))
            }
        return CapabilityRegistry.statusText(
            rows = rows,
            titleOf = { id -> AgentPolicy.packs.firstOrNull { it.id == id }?.title ?: id },
            howToEnable = { pack -> howToEnable(pack) }
        )
    }
}