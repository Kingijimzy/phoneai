package com.phoneai.app

internal enum class ShellRisk { SAFE, ASK, BLOCKED }

/** Pure shell-command triage so the AI can run harmless commands itself and must ask for risky ones. */
internal object ShellPolicy {
    private val readOnly = setOf(
        "ls", "cat", "head", "tail", "wc", "grep", "egrep", "find", "echo", "printf", "pwd", "date", "whoami", "uname", "df", "du", "stat",
        "sort", "uniq", "tr", "cut", "diff", "basename", "dirname", "which", "id", "getprop", "free", "uptime", "nl", "file", "md5sum",
        "sha256sum", "sleep", "true", "test", "[", "cd"
    )
    private val workspaceWrite = setOf("mkdir", "touch", "cp", "tee")
    private val safeGit = setOf("status", "log", "diff", "branch", "show", "remote")

    // Keep only catastrophic/designed-to-exfiltrate targets as hard blocks. Other
    // privileged or destructive commands are approval-gated by dispatch and may run
    // when the user explicitly approves them; they must not be silently impossible.
    private val blocked = listOf(
        Regex("(?i)\\brm\\s+(-[a-z]*\\s+)*(\\s*/|~|\\${'$'}HOME|\\*|/sdcard|/storage)(\\s|$)"),
        Regex(":\\(\\)\\s*\\{"),
        Regex("(?i)>\\s*/dev/(sd|block|mmcblk)"),
        Regex("(?i)\\bdd\\s+.*\\bof=/dev/(sd[a-z]+|nvme\\d+n\\d+|mmcblk\\d+)"),
        Regex("(?i)(/data/data/|/data/user/|shared_prefs|orion_[a-z_]+\\.json|\\bkeystore\\b|api[_-]?key|\\.env\\b|id_rsa|\\.ssh/)"),
        Regex("(?i)\\bchmod\\s+(-[a-z]+\\s+)?[0-7]*7[0-7]*\\s+/")
    )
    private val approvalRequired = listOf(
        Regex("(?i)\\b(curl|wget)\\b[^;&]*\\|\\s*(sh|bash|zsh)\\b"),
        Regex("(?i)\\b(mkfs|fdisk|reboot|shutdown|setprop|su|sudo)\\b"),
        Regex("(?i)\\bdd\\s+.*\\bof="),
        Regex("(?i)\\bsettings\\s+put\\b|\\bpm\\s+(uninstall|clear|disable)\\b")
    )

    private fun segments(cmd: String): List<String> =
        cmd.split(Regex("&&|\\|\\||[;|\\n]")).map { it.trim() }.filter { it.isNotEmpty() }

    private fun firstWord(seg: String): String {
        val words = seg.split(Regex("\\s+"))
        return words.firstOrNull { !it.contains('=') || it.startsWith("=") }?.removePrefix("\\") ?: ""
    }

    private fun relativeInside(path: String) = path.isNotEmpty() && !path.startsWith("/") && !path.startsWith("~") && !path.contains("..") && !path.contains("$")

    fun classify(command: String): ShellRisk {
        val cmd = command.trim()
        if (cmd.isEmpty() || cmd.length > 2000) return ShellRisk.BLOCKED
        if (blocked.any { it.containsMatchIn(cmd) }) return ShellRisk.BLOCKED
        if (approvalRequired.any { it.containsMatchIn(cmd) }) return ShellRisk.ASK
        BuiltinCommands.risk(cmd)?.let { return it }
        if (cmd.contains("`") || cmd.contains("$(") || cmd.contains("<(")) return ShellRisk.ASK
        // Redirects: only to a relative path inside the workspace.
        for (m in Regex("(>>?)\\s*([^\\s;&|]+)").findAll(cmd)) {
            val target = m.groupValues[2]
            if (target == "/dev/null" || target.startsWith("&")) continue
            if (!relativeInside(target)) return ShellRisk.ASK
        }
        for (seg in segments(cmd)) {
            val w = firstWord(seg)
            val args = seg.split(Regex("\\s+")).drop(1)
            // Automatic shell access is confined to relative workspace paths. Absolute paths,
            // home expansion, and parent traversal require a deliberate one-time approval.
            if (Regex("(?:^|\\s)(?:/[^\\s]*|~(?:/[^\\s]*)?|\\.\\.?/[^\\s]*)(?:\\s|$)").containsMatchIn(seg)) {
                return ShellRisk.ASK
            }
            when {
                w in readOnly -> {
                    if (w == "cd" && args.isNotEmpty() && !args.all { relativeInside(it) }) return ShellRisk.ASK
                    if (w == "find" && Regex("-(exec|delete|ok)\\b").containsMatchIn(seg)) return ShellRisk.ASK
                }
                w in workspaceWrite -> if (!args.filter { !it.startsWith("-") }.all { relativeInside(it) }) return ShellRisk.ASK
                w == "git" -> if (args.firstOrNull() !in safeGit) return ShellRisk.ASK
                else -> return ShellRisk.ASK
            }
        }
        return ShellRisk.SAFE
    }

    fun clip(output: String, max: Int = 6000): String =
        if (output.length <= max) output else output.take(max / 2) + "\n… [${output.length - max} chars omitted] …\n" + output.takeLast(max / 2)
}
