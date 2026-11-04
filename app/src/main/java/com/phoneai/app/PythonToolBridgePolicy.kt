package com.phoneai.app

/** Pure rules for Python calling Orion's registered tools through phone.tool(name, args). Every call still goes through the normal gate and approvals. */
internal object PythonToolBridgePolicy {
    const val MAX_CALLS_PER_RUN = 40
    private val NAME = Regex("[a-z][a-z0-9_]{1,63}")
    private val BLOCKED = setOf("python_exec")

    /** Returns an error text, or null when the call may be forwarded to the gated dispatcher. */
    fun check(name: String, callsSoFar: Int): String? = when {
        !NAME.matches(name) -> "tool name must be a registered tool id such as open_app"
        name in BLOCKED -> "python_exec cannot be called from inside Python"
        callsSoFar >= MAX_CALLS_PER_RUN -> "a Python run may make at most $MAX_CALLS_PER_RUN tool calls"
        else -> null
    }
}
