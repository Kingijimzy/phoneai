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

    /**
     * Linux backend selection is not itself a reason to interrupt the user. The command policy
     * decides: safe diagnostics run automatically, while unknown/mutating commands still require
     * confirmation through ShellPolicy.
     */
    fun requiresApproval(where: String?, command: String? = null): Boolean {
        if (backend(where) != Backend.LINUX) return false
        val value = command?.trim().orEmpty()
        return value.isBlank() || ShellPolicy.classify(value) != ShellRisk.SAFE
    }

    /** Recognize package database mutations even when chained after a shell separator. */
    fun isPackageOperation(command: String): Boolean =
        Regex("""(^|[;&|]\s*)apk\s+(add|del|upgrade|update|fix)\b""").containsMatchIn(command)

    /** Package operations legitimately need longer than an interactive shell command. */
    fun commandTimeoutMs(command: String): Long =
        if (isPackageOperation(command)) 180_000L else 60_000L
}
