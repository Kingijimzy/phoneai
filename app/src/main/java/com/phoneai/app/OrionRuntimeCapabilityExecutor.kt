package com.phoneai.app

import android.content.Context
import org.json.JSONObject

/**
 * Concrete local-runtime and file capability executor.
 * This owns the execution implementation for these backend families; Agent remains responsible
 * for task planning, policy gates, approvals, evidence and recovery.
 */
internal object OrionRuntimeCapabilityExecutor {
    fun execute(
        backend: OrionCapabilityAdapters.Backend,
        tool: String,
        args: JSONObject,
        context: Context,
        tools: Tools,
        stopped: () -> Boolean
    ): String? = when (backend) {
        OrionCapabilityAdapters.Backend.SHELL -> when (tool) {
            "shell" -> tools.shell(args.optString("command"), args.optString("where"))
            "termux_command" -> TermuxBridgeRunner.executeReadOnly(context, args.optString("command"), stopped)
            else -> null
        }
        OrionCapabilityAdapters.Backend.PYTHON -> when (tool) {
            "python_exec" -> tools.pythonExec(args.optString("code"))
            else -> null
        }
        OrionCapabilityAdapters.Backend.FILES -> when (tool) {
            "find_files" -> tools.findFiles(args.optString("query"))
            "open_file" -> tools.openFile(args.optString("id"))
            "workspace_write" -> ShellRunner.writeFile(context, args.optString("path"), args.optString("content"))
            "workspace_read" -> ShellRunner.readFile(context, args.optString("path"))
            else -> null
        }
        else -> null
    }
}
