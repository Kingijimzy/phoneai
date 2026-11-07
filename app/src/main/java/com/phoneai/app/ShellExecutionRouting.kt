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

    /** Recognize package database mutations even when chained after a shell separator. */
    fun isPackageOperation(command: String): Boolean =
        Regex("""(^|[;&|]\s*)apk\s+(add|del|upgrade|update|fix)\b""").containsMatchIn(command)

    /** Dependency downloads and project builds legitimately need longer than interactive commands. */
    fun commandTimeoutMs(command: String): Long {
        val dependencyOrBuild = Regex("""(^|[;&|]\s*)(npm\s+(install|ci)|pip3?\s+install|(?:\./)?gradlew?\s+.*(?:build\w*|test\w*|assemble\w*))""")
            .containsMatchIn(command)
        return if (isPackageOperation(command) || dependencyOrBuild) 180_000L else 60_000L
    }
}
