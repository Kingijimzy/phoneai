package com.phoneai.app

internal enum class ShellRisk { SAFE, BLOCKED }

/** Owner-authorized shell: only catastrophic device-destruction primitives are hard-blocked. */
internal object ShellPolicy {
    private val rootOrSystemWipe = listOf(
        Regex("""(?is)\b(?:rm|rmdir)\b[^;&|]*(?:\s|^)/(?:\s|$|\*|system(?:/|\s|$)|vendor(?:/|\s|$)|product(?:/|\s|$)|odm(?:/|\s|$)|boot(?:/|\s|$)|recovery(?:/|\s|$))"""),
        Regex("""(?is)\bfind\s+/(?:\s|$)[^;&|]*\s-(?:delete|exec|ok)\b""")
    )
    private val rawBlockDeviceWrite = Regex(
        """(?is)(?:\bof\s*=|>{1,2}\s*|\b(?:mkfs(?:\.\w+)?|wipefs|blkdiscard)\s+)[^;&|]*?/dev/(?:block(?:/|\b)|sd[a-z]\b|nvme\d+n\d+\b|mmcblk\d+\b|loop\d+\b|dm-\d+\b)"""
    )
    private val forkBomb = listOf(
        Regex("""(?s):\s*\(\s*\)\s*\{[^}]*\}"""),
        Regex("""(?s)\b(?:fork|bomb)\s*\(\s*\)\s*\{[^}]*\}""")
    )
    fun classify(command: String): ShellRisk {
        val cmd = command.trim()
        if (rootOrSystemWipe.any { it.containsMatchIn(cmd) }) return ShellRisk.BLOCKED
        if (rawBlockDeviceWrite.containsMatchIn(cmd)) return ShellRisk.BLOCKED
        if (forkBomb.any { it.containsMatchIn(cmd) }) return ShellRisk.BLOCKED
        return ShellRisk.SAFE
    }
    fun clip(output: String, max: Int = 6000): String =
        if (output.length <= max) output else output.take(max / 2) +
            "\n… [${output.length - max} chars omitted] …\n" + output.takeLast(max / 2)
}
