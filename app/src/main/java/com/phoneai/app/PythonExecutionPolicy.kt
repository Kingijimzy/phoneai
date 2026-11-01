package com.phoneai.app

import android.content.Context

/** Pure preflight policy for the capability-free bundled Python sandbox. */
internal object PythonExecutionPolicy {
    const val GUARDED_MAX_CODE_CHARS = 8_000
    const val MAX_CODE_CHARS = GUARDED_MAX_CODE_CHARS // compatibility alias for existing tests/callers
    const val DEVELOPER_MAX_CODE_CHARS = 16_000

    fun rejection(code: String, maxCodeChars: Int = GUARDED_MAX_CODE_CHARS): String? = when {
        code.length > maxCodeChars.coerceIn(GUARDED_MAX_CODE_CHARS, DEVELOPER_MAX_CODE_CHARS) ->
            "Python code exceeds the active profile limit of $maxCodeChars characters."
        code.isBlank() -> "Python code is required."
        code.contains('\u0000') -> "Python code contains a forbidden NUL character."
        else -> null
    }

    fun maxCodeChars(context: Context): Int = OrionOwnerDialSettings.pythonMaxCodeChars(context)
}
