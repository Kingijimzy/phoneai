package com.phoneai.app

/**
 * One source of truth for per-run limits on every code-execution surface.
 *
 * Shell, Termux, Python and JavaScript used to carry their own private constants, so a
 * change to one never reached the others and the model could not describe the real
 * limits. Enforcement still lives in each runtime (a Kotlin budget cannot stop a Python
 * loop), but the numbers and the refusal messages now come from here.
 */
data class ExecutionBudget(
    val kind: String,
    val timeoutMs: Long,
    val maxSourceChars: Int,
    val maxOutputChars: Int,
    val networkAllowed: Boolean
) {
    val timeoutSeconds: Long get() = (timeoutMs / 1000L).coerceAtLeast(1L)
}

internal object ExecutionBroker {
    /** Built-in shell runs as the app uid with network available; 30 s hard ceiling. */
    val SHELL = ExecutionBudget("shell", 30_000L, 2_000, 20_000, true)

    /** Termux is the full Linux environment; the long ceiling covers package installs. */
    val TERMUX = ExecutionBudget("termux", 180_000L, 2_000, 20_000, true)

    /** Bundled Python: no imports/files/network; 8 000 chars of source, 5 000 of output. */
    val PYTHON = ExecutionBudget("python", 5_000L, 8_000, 5_000, false)

    /** Offscreen WebView JavaScript: network loads blocked, no file access. */
    val JS = ExecutionBudget("js", 6_000L, 8_000, 8_000, false)

    fun forKind(kind: String): ExecutionBudget? = when (kind.trim().lowercase()) {
        "shell", "builtin" -> SHELL
        "termux" -> TERMUX
        "python", "py" -> PYTHON
        "js", "javascript" -> JS
        else -> null
    }

    /** Clamp a caller-supplied timeout into the budget's window. */
    fun timeoutSeconds(budget: ExecutionBudget, requestedSeconds: Long): Long =
        requestedSeconds.coerceIn(1L, budget.timeoutSeconds)

    fun sourceError(budget: ExecutionBudget, source: String): String? =
        if (source.length > budget.maxSourceChars) {
            "error: code exceeds the ${budget.maxSourceChars} character limit for ${budget.kind}"
        } else null

    fun clipOutput(budget: ExecutionBudget, text: String): String =
        if (text.length <= budget.maxOutputChars) text
        else text.take(budget.maxOutputChars) + "\n[output truncated at ${budget.maxOutputChars} characters]"

    fun describe(budget: ExecutionBudget): String =
        "${budget.kind}: ${budget.timeoutSeconds}s, source<=${budget.maxSourceChars}, " +
            "output<=${budget.maxOutputChars}, network=${if (budget.networkAllowed) "allowed" else "blocked"}"
}
