package com.phoneai.app

import org.json.JSONObject

/**
 * Adapts deterministic read-only research shortcuts to the durable Agent checkpoint lifecycle.
 * It does not grant access: callers must dispatch each step through the normal guarded executor.
 */
internal object OrionResearchShortcutLifecycle {
    data class Step(val tool: String, val args: JSONObject, val continueOnError: Boolean = false)
    data class Result(val completed: Boolean, val output: String, val completedSteps: Int, val stepOutputs: List<String> = emptyList())

    interface Checkpoint {
        fun begin(command: String): Boolean
        fun beginStep(tool: String, args: JSONObject): Boolean
        fun recordStep(tool: String, args: JSONObject, result: String): Boolean
        fun pause()
        fun requireRecovery(reason: String)
        fun complete(): Boolean
    }

    fun run(
        command: String,
        steps: List<Step>,
        checkpoint: Checkpoint,
        cancelled: () -> Boolean,
        execute: (String, JSONObject) -> String
    ): Result {
        if (command.isBlank() || steps.isEmpty()) {
            return Result(false, "error: research task has no command or steps.", 0)
        }
        if (!checkpoint.begin(command)) {
            return Result(false, "I preserved the unfinished task. Resume or stop it before starting this research request.", 0)
        }
        val outputs = mutableListOf<String>()
        var completed = 0
        for (step in steps) {
            if (cancelled()) {
                checkpoint.pause()
                return Result(false, "Research paused at a safe boundary. Completed $completed of ${steps.size} step(s).", completed)
            }
            if (!checkpoint.beginStep(step.tool, step.args)) {
                checkpoint.pause()
                return Result(false, "I could not persist the next research checkpoint, so I did not start that step.", completed)
            }
            val output = try {
                execute(step.tool, step.args)
            } catch (e: Exception) {
                "error: research step threw ${e.javaClass.simpleName}; inspect before retrying."
            }
            if (!checkpoint.recordStep(step.tool, step.args, output)) {
                checkpoint.requireRecovery("Research step '${step.tool}' returned but its result could not be durably recorded. Inspect the result before retrying.")
                return Result(false, "Research outcome is uncertain because its evidence could not be saved. Inspect before retrying.", completed)
            }
            completed++
            outputs += output
            if (output.trimStart().startsWith("error", true)) {
                checkpoint.pause()
                return Result(false, output, completed)
            }
        }
        if (cancelled()) {
            checkpoint.pause()
            return Result(false, "Research finished its reads but was paused before the completion marker. Review the saved checkpoint.", completed)
        }
        if (!checkpoint.complete()) {
            return Result(false, "Research results were collected, but I could not save the completion marker. Review the saved checkpoint before resuming.", completed)
        }
        return Result(true, outputs.joinToString("\n\n"), completed, outputs.toList())
    }

    /**
     * Runs a checkpointed research sequence whose later inputs depend on earlier read results.
     * The planner callback receives only already-recorded outputs; every proposed step is still
     * persisted before dispatch and its result persisted before another step can be planned.
     */
    fun runDynamic(
        command: String,
        checkpoint: Checkpoint,
        cancelled: () -> Boolean,
        execute: (String, JSONObject) -> String,
        nextStep: (List<String>) -> Step?
    ): Result {
        if (command.isBlank()) return Result(false, "error: research task has no command.", 0)
        if (!checkpoint.begin(command)) {
            return Result(false, "I preserved the unfinished task. Resume or stop it before starting this research request.", 0)
        }
        val outputs = mutableListOf<String>()
        var completed = 0
        while (completed < 12) {
            if (cancelled()) {
                checkpoint.pause()
                return Result(false, "Research paused at a safe boundary. Completed $completed step(s).", completed, outputs.toList())
            }
            val step = try { nextStep(outputs.toList()) } catch (e: Exception) {
                checkpoint.pause()
                return Result(false, "Research planning failed before the next dispatch (" + e.javaClass.simpleName + "); saved progress is paused.", completed, outputs.toList())
            } ?: break
            if (!checkpoint.beginStep(step.tool, step.args)) {
                checkpoint.pause()
                return Result(false, "I could not persist the next research checkpoint, so I did not start that step.", completed, outputs.toList())
            }
            val output = try {
                execute(step.tool, step.args)
            } catch (e: Exception) {
                "error: research step threw " + e.javaClass.simpleName + "; inspect before retrying."
            }
            if (!checkpoint.recordStep(step.tool, step.args, output)) {
                checkpoint.requireRecovery("Research step '" + step.tool + "' returned but its result could not be durably recorded. Inspect the result before retrying.")
                return Result(false, "Research outcome is uncertain because its evidence could not be saved. Inspect before retrying.", completed, outputs.toList())
            }
            completed++
            outputs += output
            if (output.trimStart().startsWith("error", true) && !step.continueOnError) {
                checkpoint.pause()
                return Result(false, output, completed, outputs.toList())
            }
        }
        if (completed >= 12 && runCatching { nextStep(outputs.toList()) }.getOrNull() != null) {
            checkpoint.pause()
            return Result(false, "Research stopped at its 12-step safety limit; saved progress is paused.", completed, outputs.toList())
        }
        if (completed == 0) {
            checkpoint.pause()
            return Result(false, "Research had no executable steps; nothing was dispatched.", 0, outputs.toList())
        }
        if (cancelled()) {
            checkpoint.pause()
            return Result(false, "Research finished its reads but was paused before the completion marker. Review the saved checkpoint.", completed, outputs.toList())
        }
        if (!checkpoint.complete()) {
            return Result(false, "Research results were collected, but I could not save the completion marker. Review the saved checkpoint before resuming.", completed, outputs.toList())
        }
        return Result(true, outputs.joinToString("\n\n"), completed, outputs.toList())
    }
}
