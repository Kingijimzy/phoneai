package com.phoneai.app

import android.content.Context

/**
 * Stage 2: one place that says which tools are usable right now and why not.
 * The model is told this in its prompt, so it reports the real cause ("the shell pack is off, switch it on")
 * instead of concluding that Orion lacks the capability. Pure logic, unit tested.
 */
internal object ToolGate {

    /** Groups unavailable tools by cause. Returns "" when everything is ready, so the prompt stays short. */
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
        val sb = StringBuilder("TOOL STATUS RIGHT NOW (true for this moment; use it, do not guess):\n")
        offByPack.forEach { (pack, list) ->
            sb.append("- ").append(titleOf(pack)).append(" is OFF, so these are unavailable: ")
                .append(list.take(6).joinToString(", ")).append(if (list.size > 6) " and ${list.size - 6} more" else "")
                .append(". Tell the user to switch it on in ").append(howToEnable(pack)).append(". The feature exists; it is not missing.\n")
        }
        if (denied.isNotEmpty()) {
            sb.append("- The user has set these to DENY: ").append(denied.take(8).joinToString(", "))
                .append(". Do not use them; say the user can change this in Tool permissions.\n")
        }
        sb.append("- Everything else is ready. Tools marked ASK ask the user once per task.")
        return sb.toString()
    }

    /**
     * The first gate, run before any user prompt: a tool the user set to DENY, or a tool whose pack is switched off,
     * is refused immediately. Previously Orion could ask "allow this tool?" and then refuse it anyway.
     */
    fun preflightDenial(c: Context, tool: String): String? {
        val denied = ToolPermissions.mode(c, tool) == ToolMode.DENY
        val pack = AgentPolicy.packForTool(tool)
        val packOn = pack == null || AgentPolicy.enabled(c, pack)
        if (CapabilityRegistry.forBuiltInTool(tool, denied, packOn).usable) return null
        return if (denied) ToolPermissionPolicy.denyMessage(tool)
        else pack?.let { "error: " + AgentPolicy.packOffMessage(it) }
    }

    /** Where the user switches a pack on. Kept in one place so the model and the screens agree. */
    fun howToEnable(pack: String): String = when (pack) {
        "connected_services" -> "the Capability board (Connected services)"
        "workspace_shell" -> "Agent control > Packs > Workspace shell"
        "orion_core" -> "Agent control > Packs > Orion core"
        else -> "Agent control > Packs"
    }

    /** The live status for the system prompt, read from the same settings the dispatcher uses. */
    fun statusFor(c: Context): String = summarize(
        tools = ToolRegistry.all.map { it.name },
        modeOf = { ToolPermissions.mode(c, it) },
        packOf = { AgentPolicy.packForTool(it) },
        packOn = { AgentPolicy.enabled(c, it) },
        titleOf = { id -> AgentPolicy.packs.firstOrNull { it.id == id }?.title ?: id }
    )
}
