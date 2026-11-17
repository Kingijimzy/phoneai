package com.phoneai.app

/** Strict shell backend routing: never silently downgrade an explicit Linux request to Android sh. */
internal object ShellExecutionRouting {
    enum class Backend { BUILTIN, LINUX, UBUNTU, TERMUX, INVALID }

    fun backend(where: String?): Backend = when (where?.trim()?.lowercase()) {
        null, "", "builtin", "built-in", "android" -> Backend.BUILTIN
        "linux", "alpine" -> Backend.LINUX
        "ubuntu", "debian" -> Backend.UBUNTU
        "termux" -> Backend.TERMUX
        else -> Backend.INVALID
    }

    /** Recognize package database mutations even when chained after a shell separator. */
    fun isPackageOperation(command: String): Boolean =
        Regex("""(^|[;&|]\s*)apk\s+(add|del|upgrade|update|fix)\b""").containsMatchIn(command)

    fun isAptOperation(command: String): Boolean =
        Regex("""(^|[;&|]\s*)(sudo\s+)?(apt|apt-get|dpkg)\s+(install|update|upgrade|dist-upgrade|remove|purge|-i)\b""").containsMatchIn(command)

    /** Dependency downloads and project builds legitimately need longer than interactive commands. */
    fun commandTimeoutMs(command: String): Long {
        val dependencyOrBuild = Regex("""(^|[;&|]\s*)(npm\s+(install|ci|run\s+build)|(?:yarn|pnpm)\s+(install|add|build)|python3?\s+-m\s+pip\s+install|cargo\s+(build|install)|git\s+clone|pytest|make|mvn\s+\w+|pip3?\s+install|(?:\./)?gradlew?\s+.*(?:build\w*|test\w*|assemble\w*))""")
            .containsMatchIn(command)
        return if (isPackageOperation(command) || isAptOperation(command) || dependencyOrBuild) 600_000L else 300_000L
    }
}
