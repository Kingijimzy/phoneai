package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owns Dev Agent and Macro Protocol tool execution.
 * All calls route through OrionDevAgent and OrionMacroExecutor, which enforce
 * ShellPolicy hard blocks, read-only-by-default workspace access, and honest
 * reporting (code written ≠ compiled ≠ tested ≠ installed).
 */
internal class OrionDevCapabilityExecutor(
    private val context: Context,
    private val pythonExecutor: OrionPythonCapabilityExecutor?
) {
    private val devAgent = OrionDevAgent(context, pythonExecutor ?: OrionPythonCapabilityExecutor(context, null))
    private val macroExecutor = OrionMacroExecutor(context)

    private val supportedTools = setOf(
        "dev_understand", "dev_read", "dev_write", "dev_python", "dev_shell", "dev_plan", "dev_summary",
        "macro_save", "macro_run", "macro_list", "macro_delete"
    )

    fun supports(tool: String): Boolean = tool in supportedTools

    fun execute(
        tool: String,
        args: JSONObject,
        confirm: (String) -> Boolean = { false },
        stopped: () -> Boolean = { false }
    ): String? {
        if (!supports(tool)) return null
        if (stopped()) return "error: task was stopped before Dev Agent execution."

        return when (tool) {
            "dev_understand" -> {
                val path = args.optString("path", ".")
                devAgent.understandRepository(path).let { r ->
                    if (!r.blocker.isNullOrBlank()) "error: ${r.blocker}" else r.summary + " " + r.evidence.take(1500)
                }
            }
            "dev_read" -> {
                val path = args.optString("path")
                if (path.isBlank()) "error: path is required"
                else devAgent.readSourceFile(path).let { r ->
                    if (!r.blocker.isNullOrBlank()) "error: ${r.blocker}" else r.summary + "\n" + r.evidence
                }
            }
            "dev_write" -> {
                val path = args.optString("path")
                val content = args.optString("content")
                if (path.isBlank()) "error: path is required"
                else if (content.isBlank()) "error: content is required"
                else devAgent.writeSourceFile(path, content).let { r ->
                    if (!r.blocker.isNullOrBlank()) "error: ${r.blocker}" else r.summary
                }
            }
            "dev_python" -> {
                val code = args.optString("code")
                if (code.isBlank()) "error: code is required"
                else devAgent.runPythonCode(code, confirm, stopped).let { r ->
                    if (!r.blocker.isNullOrBlank()) "error: ${r.blocker}" else r.evidence
                }
            }
            "dev_shell" -> {
                val command = args.optString("command")
                if (command.isBlank()) "error: command is required"
                else devAgent.runShellCommand(command, stopped).let { r ->
                    if (!r.blocker.isNullOrBlank()) "error: ${r.blocker}" else r.evidence
                }
            }
            "dev_plan" -> {
                val path = args.optString("path")
                val proposedChange = args.optString("proposed_change")
                if (path.isBlank()) "error: path is required"
                else if (proposedChange.isBlank()) "error: proposed_change is required"
                else devAgent.planChange(path, proposedChange).let { r ->
                    if (!r.blocker.isNullOrBlank()) "error: ${r.blocker}" else r.summary + "\n" + r.evidence
                }
            }
            "dev_summary" -> devAgent.projectSummary().let { r ->
                if (!r.blocker.isNullOrBlank()) "error: ${r.blocker}" else r.summary
            }
            "macro_save" -> {
                val name = args.optString("name")
                val steps = args.optJSONArray("steps")
                if (name.isBlank()) "error: name is required"
                else if (steps == null) "error: steps array is required"
                else if (macroExecutor.saveMacro(name, steps)) "Macro routine '$name' saved with ${steps.length()} steps."
                else "error: macro '$name' could not be saved"
            }
            "macro_run" -> {
                val name = args.optString("name")
                if (name.isBlank()) "error: name is required"
                else {
                    val run = macroExecutor.executeMacro(name, toolInvoker = { toolName, toolArgs ->
                        dispatchMacroTool(toolName, toolArgs, confirm, stopped)
                    }, confirm = confirm, stopped = stopped)
                    when (run.status) {
                        "completed" -> "Macro '$name' completed: ${run.completedSteps}/${run.totalSteps} steps verified. " +
                            run.results.entries.joinToString("; ") { "step ${it.key}: ${it.value.take(120)}" }
                        "failed" -> "Macro '$name' failed at step ${run.failedStep ?: "?"}. " +
                            run.results.entries.joinToString("; ") { "step ${it.key}: ${it.value.take(120)}" }
                        "cancelled" -> "Macro '$name' was cancelled after ${run.completedSteps}/${run.totalSteps} steps."
                        else -> "Macro '$name' ended with unknown status: ${run.status}"
                    }
                }
            }
            "macro_list" -> {
                val macros = macroExecutor.listMacros()
                if (macros.isEmpty()) "No saved macro routines yet."
                else macros.joinToString("\n") { "- $it" }
            }
            "macro_delete" -> {
                val name = args.optString("name")
                if (name.isBlank()) "error: name is required"
                else if (macroExecutor.deleteMacro(name)) "Macro routine '$name' deleted."
                else "error: macro '$name' not found or could not be deleted"
            }
            else -> null
        }
    }

    private fun dispatchMacroTool(
        tool: String,
        args: JSONObject,
        confirm: (String) -> Boolean,
        stopped: () -> Boolean
    ): String? {
        // Macro steps can only use read-safe workspace and python tools
        return when (tool) {
            "workspace_read" -> {
                val path = args.optString("path")
                if (path.isBlank()) "error: path is required"
                else OrionPythonWorkspace(context).read(path)
            }
            "workspace_write" -> {
                val path = args.optString("path")
                val content = args.optString("content")
                if (path.isBlank()) "error: path is required"
                else OrionPythonWorkspace(context).write(path, content)
            }
            "workspace_list" -> OrionPythonWorkspace(context).list()
            "python_exec" -> pythonExecutor?.execute("python_exec", args, confirm, stopped) ?: "error: python executor unavailable"
            else -> "error: macro step tool '$tool' is not permitted in macro routines"
        }
    }
}