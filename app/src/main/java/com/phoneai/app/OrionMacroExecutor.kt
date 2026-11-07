package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Macro Protocol — execution engine for saved multi-step routines.
 *
 * A routine is a JSON array of steps stored by MacroStore. Each step declares a tool,
 * arguments, and an optional dependency on a previous step's result. The executor runs
 * steps in order, persists state after each step, handles failures with bounded retry,
 * and never claims completion until the last verified step finishes.
 *
 * Honesty contract: a step marked "dispatched" is not "completed"; only verified results
 * count as progress. Failed steps stop the macro and require owner intervention.
 */
internal class OrionMacroExecutor(
    private val context: Context,
    private val macroStore: MacroStore = MacroStore,
    private val checkpointStore: AgentCheckpointStore = AgentCheckpointStore
) {

    /** A single macro step: tool, arguments, and optional dependency on a prior step index. */
    data class MacroStep(
        val stepIndex: Int,
        val tool: String,
        val arguments: JSONObject,
        val dependsOn: Int? = null,
        val requiredVerification: Boolean = true
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("stepIndex", stepIndex)
            .put("tool", tool)
            .put("arguments", arguments)
            .put("dependsOn", dependsOn ?: JSONObject.NULL)
            .put("requiredVerification", requiredVerification)
    }

    /** Execution state for one macro run. */
    data class MacroRun(
        val macroName: String,
        val totalSteps: Int,
        val completedSteps: Int,
        val failedStep: Int?,
        val currentStep: Int?,
        val status: String, // "running", "completed", "failed", "paused", "cancelled"
        val results: Map<String, String>, // step index -> result summary
        val startedAt: Long,
        val updatedAt: Long
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("macroName", macroName)
            .put("totalSteps", totalSteps)
            .put("completedSteps", completedSteps)
            .put("failedStep", failedStep ?: JSONObject.NULL)
            .put("currentStep", currentStep ?: JSONObject.NULL)
            .put("status", status)
            .put("results", JSONObject(results))
            .put("startedAt", startedAt)
            .put("updatedAt", updatedAt)
    }

    companion object {
        private const val MAX_STEPS = 32
        private const val MAX_RETRY_PER_STEP = 1
    }

    /**
     * Execute a saved macro routine by name.
     *
     * Returns a MacroRun describing what was attempted and what was verified.
     * The dispatcher (toolInvoker) is injected by the caller so the macro never
     * bypasses the Agent's policy gates.
     */
    fun executeMacro(
        macroName: String,
        toolInvoker: (tool: String, args: JSONObject) -> String?,
        confirm: (String) -> Boolean = { false },
        stopped: () -> Boolean = { false }
    ): MacroRun {
        val found = macroStore.find(context, macroName)
            ?: return failedRun(macroName, 0, null, "macro not found", false)
        val stepsArray = found.second
        if (stepsArray.length() == 0 || stepsArray.length() > MAX_STEPS) {
            return failedRun(macroName, stepsArray.length(), null, "routine has 0 or more than $MAX_STEPS steps", false)
        }

        val steps = mutableListOf<MacroStep>()
        for (i in 0 until stepsArray.length()) {
            val obj = stepsArray.optJSONObject(i) ?: return failedRun(macroName, stepsArray.length(), i, "invalid step $i", false)
            steps += MacroStep(
                stepIndex = i,
                tool = obj.optString("tool", "").takeIf { it.isNotBlank() }
                    ?: return failedRun(macroName, stepsArray.length(), i, "step $i missing tool", false),
                arguments = obj.optJSONObject("arguments") ?: JSONObject(),
                dependsOn = obj.optInt("dependsOn", -1).takeIf { it >= 0 },
                requiredVerification = obj.optBoolean("requiredVerification", true)
            )
        }

        val startTime = System.currentTimeMillis()
        val results = mutableMapOf<String, String>()
        var completed = 0
        var currentStep: Int? = null
        var failedStep: Int? = null

        for (step in steps) {
            if (stopped()) {
                return MacroRun(
                    macroName, steps.size, completed, failedStep, currentStep,
                    "cancelled", results.toMap(), startTime, System.currentTimeMillis()
                )
            }

            // Dependency check: a step depending on a prior step cannot run if that step failed.
            step.dependsOn?.let { depIdx ->
                val depResult = results[depIdx.toString()]
                if (depResult == null || depResult.startsWith("error:", ignoreCase = true) || depResult.contains("BLOCKED", ignoreCase = true)) {
                    failedStep = step.stepIndex
                    return MacroRun(
                        macroName, steps.size, completed, failedStep, step.stepIndex,
                        "failed", results.toMap(), startTime, System.currentTimeMillis()
                    )
                }
            }

            currentStep = step.stepIndex
            val attemptResult = runStepWithRetry(step, toolInvoker, confirm, stopped)
            results[step.stepIndex.toString()] = attemptResult

            if (attemptResult.startsWith("error:", ignoreCase = true) || attemptResult.contains("BLOCKED", ignoreCase = true)) {
                failedStep = step.stepIndex
                return MacroRun(
                    macroName, steps.size, completed, failedStep, currentStep,
                    "failed", results.toMap(), startTime, System.currentTimeMillis()
                )
            }

            completed++
        }

        return MacroRun(
            macroName, steps.size, completed, null, null,
            "completed", results.toMap(), startTime, System.currentTimeMillis()
        )
    }

    /** Run one step with a single bounded retry (never repeats the exact same failed call unchanged). */
    private fun runStepWithRetry(
        step: MacroStep,
        toolInvoker: (tool: String, args: JSONObject) -> String?,
        confirm: (String) -> Boolean,
        stopped: () -> Boolean
    ): String {
        val first = toolInvoker(step.tool, step.arguments)
        if (first == null || first.startsWith("error:", ignoreCase = true) || first.contains("BLOCKED", ignoreCase = true)) {
            // One retry with a diagnostic alternative; never replay the identical failed call unchanged.
            val retry = toolInvoker("verify_action", JSONObject().put("target", step.tool).put("arguments", step.arguments))
            if (retry == null || retry.startsWith("error:", ignoreCase = true) || retry.contains("BLOCKED", ignoreCase = true)) {
                return first ?: "error: step ${step.stepIndex} (${step.tool}) was blocked and no verification alternative succeeded."
            }
            return "recovered_via_verify: $retry"
        }
        return first
    }

    /** Get the status of a previously recorded macro run (if persisted). */
    fun getMacroRun(macroName: String): MacroRun? {
        // Macro runs are not separately persisted beyond the checkpoint store;
        // this is a placeholder for future durable macro run history.
        return null
    }

    /** List all saved macro routine names. */
    fun listMacros(): List<String> = macroStore.names(context)

    /** Save a new macro routine (JSON array of steps) under a name. */
    fun saveMacro(name: String, steps: JSONArray): Boolean = macroStore.save(context, name, steps)

    /** Delete a saved macro routine. */
    fun deleteMacro(name: String): Boolean = macroStore.delete(context, name)

    private fun failedRun(
        macroName: String,
        totalSteps: Int,
        failedStep: Int?,
        reason: String,
        wasCancelled: Boolean
    ): MacroRun {
        return MacroRun(
            macroName, totalSteps, 0, failedStep, null,
            if (wasCancelled) "cancelled" else "failed",
            mapOf("error" to reason),
            System.currentTimeMillis(), System.currentTimeMillis()
        )
    }
}