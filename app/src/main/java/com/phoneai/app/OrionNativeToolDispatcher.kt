package com.phoneai.app

import org.json.JSONObject

/** Serializes native model tool calls through Central Brain's existing guarded executor. */
internal object OrionNativeToolDispatcher {
    const val MAX_CALLS_PER_TURN = 32
    const val MAX_RESULT_CHARS = 96_000
    const val MAX_ROUNDS_PER_COMMAND = 200

    data class Outcome(val call: OrionModelToolCall, val content: String, val dispatched: Boolean)

    /**
     * Provider adapters may deliver tool calls through a synchronous callback instead of
     * returning them in the turn payload. Both routes must enforce the same contract before
     * entering the guarded executor.
     */
    fun dispatchCallback(
        name: String,
        args: JSONObject,
        cancelled: () -> Boolean,
        isRegistered: (String) -> Boolean,
        execute: (String, JSONObject) -> String
    ): String {
        if (cancelled()) return "error: cancelled before dispatch; no action was started."
        if (!isRegistered(name)) return "error: tool '$name' is not registered or available in this Orion build; no action was dispatched."
        val contract = ToolRegistry.find(name)
            ?: return "error: tool '$name' has no authoritative registered argument contract; no action was dispatched."
        val validationError = OrionToolContractCodec.validateArguments(contract, args)
        if (validationError != null) return "error: invalid arguments for '$name': $validationError. No action was dispatched."
        return try {
            execute(name, JSONObject(args.toString())).take(MAX_RESULT_CHARS)
        } catch (failure: Exception) {
            "error: ${failure.message ?: failure.javaClass.simpleName}"
        }
    }

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
                ToolRegistry.find(call.name) == null -> Outcome(
                    call,
                    "error: tool '${call.name}' has no authoritative registered argument contract; no action was dispatched.",
                    false
                )
                OrionToolContractCodec.validateArguments(ToolRegistry.find(call.name)!!, args) != null -> Outcome(
                    call,
                    "error: invalid arguments for '${call.name}': ${OrionToolContractCodec.validateArguments(ToolRegistry.find(call.name)!!, args)}. No action was dispatched.",
                    false
                )
                else -> {
                    val result = try {
                        execute(call.name, args)
                    } catch (failure: Exception) {
                        "error: ${failure.message ?: failure.javaClass.simpleName}"
                    }
                    Outcome(call, result.take(MAX_RESULT_CHARS), true)
                }
            }
            outcomes += outcome
        }
        return outcomes
    }
}
