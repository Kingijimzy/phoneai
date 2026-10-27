package com.phoneai.app

/**
 * Last-mile dispatch boundary for the agent loop.
 *
 * Approval, pack, scope, permission, and tool-specific safety checks must run before this helper.
 * This layer prevents unregistered/stopped work from reaching an executor and distinguishes a
 * returned result from a verified outcome. It deliberately does not decide whether the user's
 * overall goal is complete.
 */
internal object OrionExecutionLoop {
    enum class DispatchState { BLOCKED, FAILED, DISPATCHED }

    data class DispatchResult(
        val state: DispatchState,
        val value: String
    )

    fun dispatch(
        tool: String,
        stopped: Boolean,
        execute: () -> String
    ): DispatchResult {
        if (stopped) {
            return DispatchResult(DispatchState.BLOCKED, "error: task was stopped before dispatch.")
        }
        if (tool.isBlank() || ToolRegistry.all.none { it.name == tool }) {
            return DispatchResult(DispatchState.BLOCKED, "error: refusing to dispatch an unregistered tool.")
        }
        return try {
            val value = execute()
            if (value.trimStart().startsWith("error", ignoreCase = true)) {
                DispatchResult(DispatchState.FAILED, value)
            } else {
                DispatchResult(DispatchState.DISPATCHED, value)
            }
        } catch (e: Exception) {
            DispatchResult(
                DispatchState.FAILED,
                "error: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    /** A dispatch result alone is never completion proof. */
    fun completionEvidence(
        result: String,
        verificationRequired: Boolean,
        verificationPassed: Boolean
    ): Boolean {
        if (result.trimStart().startsWith("error", ignoreCase = true)) return false
        return !verificationRequired || verificationPassed
    }
}
