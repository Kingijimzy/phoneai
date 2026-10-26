package com.phoneai.app

/** Pure safety checks for explicit agent text entry through Orion's IME. */
internal object OrionKeyboardPolicy {
    const val MAX_TEXT_LENGTH = 4000

    fun validateRequest(text: String): String? = when {
        text.isEmpty() -> "error: refusing to type empty text"
        text.length > MAX_TEXT_LENGTH -> "error: text exceeds the ${MAX_TEXT_LENGTH} character limit"
        else -> null
    }

    fun validateFocusedEditor(
        targetPackage: String?,
        focusedPackage: String?,
        isPassword: Boolean,
        hasConnection: Boolean
    ): String? = when {
        isPassword -> "error: refusing to type into a password field"
        !targetPackage.isNullOrBlank() && focusedPackage != targetPackage ->
            "error: focused editor changed while switching keyboards; text was not entered"
        !hasConnection -> "error: no input connection is available for the focused field"
        else -> null
    }

    /** Failing to select-all must never fall back to append, which can duplicate or corrupt text. */
    fun canReplaceField(selectAllSucceeded: Boolean): Boolean = selectAllSucceeded
}
