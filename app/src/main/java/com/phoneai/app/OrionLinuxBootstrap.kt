package com.phoneai.app

/**
 * Lets Orion expand its own Linux toolbox: when a command fails because a tool is missing from the
 * Alpine guest, work out which apk packages provide it so the command can be retried after install.
 */
internal object OrionLinuxBootstrap {
    private val packages = linkedMapOf(
        "pip3" to "py3-pip", "pip" to "py3-pip",
        "node" to "nodejs", "npm" to "nodejs npm",
        "git" to "git", "curl" to "curl", "wget" to "wget",
        "gcc" to "build-base", "g++" to "build-base", "make" to "build-base", "cc" to "build-base",
        "java" to "openjdk17-jre", "javac" to "openjdk17",
        "ruby" to "ruby", "go" to "go", "cargo" to "cargo", "rustc" to "rust",
        "jq" to "jq", "unzip" to "unzip", "zip" to "zip", "ffmpeg" to "ffmpeg", "sqlite3" to "sqlite",
        "php" to "php", "perl" to "perl", "lua" to "lua5.4", "bash" to "bash", "nano" to "nano", "vim" to "vim"
    )

    /** Packages to apk-add so [command] can run, judged from the command words and the failure text. */
    fun missingPackages(command: String, output: String): List<String> {
        if (!Regex("(?i)(not found|no such file|inaccessible|command not found|not installed)").containsMatchIn(output)) return emptyList()
        val words = command.split(Regex("""[\s;&|()]+""")).filter { it.isNotBlank() }.toSet()
        return packages.filter { (tool, _) -> tool in words && Regex("""(?i)\b${Regex.escape(tool)}\b[^\n]{0,40}(not found|no such file|inaccessible)|(not found|no such file|inaccessible)[^\n]{0,40}\b${Regex.escape(tool)}\b""").containsMatchIn(output) }
            .values.flatMap { it.split(' ') }.distinct()
    }

    fun installCommand(pkgs: List<String>) = "apk add --no-cache " + pkgs.joinToString(" ")
}
