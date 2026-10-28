package com.phoneai.app

/** Strict shell backend routing: never silently downgrade an explicit Linux request to Android sh. */
internal object ShellExecutionRouting {
    enum class Backend { BUILTIN, LINUX, TERMUX, INVALID }

    fun backend(where: String?): Backend = when (where?.trim()?.lowercase()) {
        null, "", "builtin", "built-in", "android" -> Backend.BUILTIN
        "linux", "alpine" -> Backend.LINUX
        "termux" -> Backend.TERMUX
        else -> Backend.INVALID
    }

    /** Linux commands always require explicit confirmation, including the Alpine alias. */
    fun requiresApproval(where: String?): Boolean = backend(where) == Backend.LINUX

    /** Package operations legitimately need longer than an interactive shell command. */
    fun commandTimeoutMs(command: String): Long {
        val packageOperation = Regex("""(^|[;&|]\s*)apk\s+(add|upgrade|update|fix)\b""")
            .containsMatchIn(command)
        return if (packageOperation) 180_000L else 60_000L
    }
}
