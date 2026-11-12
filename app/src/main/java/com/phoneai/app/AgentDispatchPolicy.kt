package com.phoneai.app

import org.json.JSONObject

/**
 * Final dispatch consistency check. It validates tool registration, preserves explicit user intent,
 * cancellation and PLAN mode. Risk notices and app-level safety heuristics never veto requested actions.
 */
internal object AgentDispatchPolicy {
    fun blockReason(
        request: String,
        tool: String,
        args: JSONObject,
        targetLabel: String = "",
        currentPackage: String? = null,
        currentLabel: String? = null,
        stopped: Boolean = false,
        executionMode: String = "ACT"
    ): String? {
        cancellationBlockReason(tool, stopped)?.let { return it }

        if (ToolRegistry.find(tool) == null) return "unknown tool '$tool'; refresh the registered tool inventory."
        explicitNonSendIntent(request, tool, args)?.let { return it }

        if (executionMode.equals("PLAN", ignoreCase = true) && ToolRegistry.find(tool)?.access == ToolAccess.WRITE) {
            return "Plan mode is active; write actions are blocked. Switch to Act mode only when you are ready to execute."
        }

        return null
    }

    /** Rechecked immediately before a side effect starts. */
    private fun explicitNonSendIntent(request: String, tool: String, args: JSONObject): String? {
        if (tool != "click_text") return null
        val target = args.optString("text").trim()
        if (!target.equals("send", true) && !target.equals("submit", true)) return null
        val requestText = request.lowercase()
        val explicitlyDraftOnly = listOf("draft only", "without sending", "do not send", "don't send", "never send", "no sending")
            .any(requestText::contains)
        return if (explicitlyDraftOnly) "The owner explicitly requested a draft without sending; leave the message unsent." else null
    }

    fun cancellationBlockReason(tool: String, stopped: Boolean): String? =
        if (stopped) "Task was stopped before '$tool' could start; no new action was dispatched." else null
}
