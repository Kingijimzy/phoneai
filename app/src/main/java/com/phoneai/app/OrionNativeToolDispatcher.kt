package com.phoneai.app

import org.json.JSONObject

/** Serializes native model tool calls through Central Brain's existing guarded executor. */
internal object OrionNativeToolDispatcher {
    const val MAX_CALLS_PER_TURN = 8

    data class Outcome(val call: OrionModelToolCall, val content: String, val dispatched: Boolean)

    fun dispatch(
        calls: List<OrionModelToolCall>,
        cancelled: () -> Boolean,
        isRegistered: (String) -> Boolean,
        execute: (String, JSONObject) -> String
    ): List<Outcome> {
        val outcomes = ArrayList<Outcome>()
        for (call in calls.take(MAX_CALLS_PER_TURN)) {
            val args = JSONObject(call.arguments.toString())
            val outcome = when {
                cancelled() -> Outcome(call, "error: cancelled before dispatch; no action was started.", false)
                call.validationError != null -> Outcome(
                    call,
                    "error: malformed arguments for '${call.name}': ${call.validationError}. No action was dispatched.",
                    false
                )
                !call.available || !isRegistered(call.name) -> Outcome(
                    call,
                    "error: tool '${call.name}' is not registered or available in this Orion build; no action was dispatched.",
                    false
                )
                else -> {
                    val result = try {
                        execute(call.name, args)
                    } catch (failure: Exception) {
                        "error: ${failure.message ?: failure.javaClass.simpleName}"
                    }
                    Outcome(call, result.take(8000), true)
                }
            }
            outcomes += outcome
        }
        return outcomes
    }
}
