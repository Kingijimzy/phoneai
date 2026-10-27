package com.phoneai.app

/** Pure preflight policy for the bundled Python interpreter. */
internal object PythonExecutionPolicy {
    const val MAX_CODE_CHARS = 8_000

    fun rejection(code: String, explicitlyApproved: Boolean): String? = when {
        !explicitlyApproved -> "Python execution requires explicit user approval."
        code.isBlank() -> "Python code is required."
        code.length > MAX_CODE_CHARS -> "Python code exceeds $MAX_CODE_CHARS characters."
        code.contains('\u0000') -> "Python code contains a forbidden NUL character."
        else -> null
    }
}
