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
     * Backend selection is not itself a reason to interrupt the user. Apply the same command
     * risk policy to built-in, Alpine and Termux shells; only the command's actual risk matters.
     */
    fun requiresApproval(where: String?, command: String? = null): Boolean {
        val selectedBackend = backend(where)
        val value = command?.trim().orEmpty()
        if (value.isBlank()) return selectedBackend == Backend.LINUX
        return ShellPolicy.classify(value) != ShellRisk.SAFE
    }

    /** Recognize package database mutations even when chained after a shell separator. */
    fun isPackageOperation(command: String): Boolean =
        Regex("""(^|[;&|]\s*)apk\s+(?:(?:--no-cache|--repositories-file\s+\S+|--repository\s+\S+)\s+)*(add|del|upgrade|update|fix)\b""")
            .containsMatchIn(command)

    /** Dependency downloads and project builds legitimately need longer than interactive commands. */
    fun commandTimeoutMs(command: String): Long {
        val dependencyOrBuild = Regex("""(^|[;&|]\s*)(npm\s+(install|ci)|pip3?\s+install|(?:\./)?gradlew?\s+.*(?:build\w*|test\w*|assemble\w*))""")
            .containsMatchIn(command)
        return if (isPackageOperation(command) || dependencyOrBuild) 180_000L else 60_000L
    }
}
