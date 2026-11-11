package com.phoneai.app

enum class ShellRisk { SAFE, BLOCKED }

/**
 * Orion's owner-free shell policy.
 *
 * Ordinary commands, installers, credential-file access and system configuration are not
 * blocked by an application-level policy. The sole interactive boundary is destructive
 * deletion/wiping: the exact command must be confirmed by the owner in chat.
 *
 * Android's own sandbox, runtime permissions and backend limitations remain authoritative.
 */
internal object ShellPolicy {
    private val destructiveDelete = listOf(
        Regex("""(?is)(?:^|[;&|]\s*)(?:command\s+)?(?:rm|rmdir|unlink|shred)\b"""),
        Regex("""(?is)\bfind\b[^;&|]*\s-delete\b"""),
        Regex("""(?is)\b(?:wipe|erase|factory[_ -]?reset|format)\b"""),
        Regex("""(?is)\btruncate\s+(?:-s\s*0\s+)?[^;&|]+"""),
        Regex("""(?is)\b(?:wipefs|blkdiscard)\b""")
    )

    private val confirmedCommands = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** The owner confirmed this exact destructive command in chat. */
    fun confirm(command: String) {
        confirmedCommands.add(command.trim())
    }

    fun classify(command: String): ShellRisk {
        val exact = command.trim()
        if (exact.isBlank() || exact in confirmedCommands) return ShellRisk.SAFE
        return if (destructiveDelete.any { it.containsMatchIn(exact) }) ShellRisk.BLOCKED else ShellRisk.SAFE
    }

    fun clip(output: String, max: Int = 6000): String =
        if (output.length <= max) output else output.take(max / 2) +
            "\n… [${output.length - max} chars omitted] …\n" + output.takeLast(max / 2)
}
