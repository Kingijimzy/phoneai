package com.phoneai.app

import org.json.JSONObject

/**
 * Final agent dispatch gate. All model-proposed actions pass through this policy immediately
 * before execution, so task scope, request intent, sensitive-screen rules, and cancellation
 * are checked together at the real dispatch boundary.
 *
 * This policy never grants approval; consequential-action confirmation remains a separate,
 * fresh user interaction after this gate succeeds.
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

        if (executionMode.equals("PLAN", ignoreCase = true) && ToolRegistry.find(tool)?.access == ToolAccess.WRITE) {
            return "Plan mode is active; write actions are blocked. Switch to Act mode only when you are ready to execute."
        }

        TaskScopePolicy.blockReason(request, tool)?.let {
            return "task scope guard blocked tool. $it"
        }

        AgentHooks.scopeBlockReason(request, tool, args, targetLabel)?.let {
            return "scope guard blocked action. $it"
        }

        val target = when (tool) {
            "open_app" -> args.optString("name")
            "browser_type" -> args.optString("target")
            "open_deep_link" -> args.optString("uri")
            else -> null
        }
        val text = if (tool == "type" || tool == "browser_type") args.optString("text") else null
        SafetyGate.blockReason(tool, target, text, currentPackage, currentLabel)?.let {
            return "safety gate blocked action. $it"
        }

        return null
    }

    /** Rechecked after any approval prompt, immediately before the side effect starts. */
    fun cancellationBlockReason(tool: String, stopped: Boolean): String? =
        if (stopped) "Task was stopped before '$tool' could start; no new action was dispatched." else null
}
