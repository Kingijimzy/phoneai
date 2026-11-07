package com.phoneai.app

enum class ShellRisk { SAFE, BLOCKED }

/**
 * Minimal shell policy. Only catastrophic device-destruction primitives are hard-blocked.
 * Ordinary workspace and system commands execute without per-command approval.
 */
internal object ShellPolicy {
    private val factoryResetProtection = listOf(
        Regex("""(?is)\b(?:factory.reset|wipe.data|wipe.cache|format.userdata|erase.all|factory_reset|--wipe\s+(?:all|data))"""),
        Regex("""(?is)\brecovery\s+--wipe\s+(?:data|cache|all)\b"""),
        Regex("""(?is)\bfastboot\s+(?:erase|format|wipe)\b"""),
    )
    private val rawBlockDeviceWrite = listOf(
        Regex("""(?is)\bof\s*=\s*[^;&|]*?/dev/(?:block(?:/|\b)|sd[a-z]\b|nvme\d+n\d+\b|mmcblk\d+\b|loop\d+\b|dm-\d+\b)"""),
        Regex("""(?is)>{1,2}\s*[^;&|]*?/dev/(?:block(?:/|\b)|sd[a-z]\b|nvme\d+n\d+\b|mmcblk\d+\b|loop\d+\b|dm-\d+\b)"""),
        Regex("""(?is)\b(?:mkfs(?:\.\w+)?|wipefs|blkdiscard)\b[^;&|]*?/dev/(?:block(?:/|\b)|sd[a-z]\b|nvme\d+n\d+\b|mmcblk\d+\b|loop\d+\b|dm-\d+\b)"""),
    )
    private val forkBomb = listOf(
        Regex("""(?s):\s*\(\s*\)\s*\{[^}]*\}"""),
    )

    private fun inspectionForms(command: String): List<String> {
        val raw = command.trim()
        val dequoted = raw.replace("'", "").replace("\"", "").replace("`", "")
            .replace("\\", "").lowercase()
            .replace(Regex("\\s+"), " ")
        return listOf(raw, dequoted).distinct()
    }

    fun classify(command: String): ShellRisk {
        val forms = inspectionForms(command)
        if (forms.any { form -> factoryResetProtection.any { it.containsMatchIn(form) } }) return ShellRisk.BLOCKED
        if (forms.any { form -> rawBlockDeviceWrite.any { it.containsMatchIn(form) } }) return ShellRisk.BLOCKED
        if (forms.any { form -> forkBomb.any { it.containsMatchIn(form) } }) return ShellRisk.BLOCKED
        return ShellRisk.SAFE
    }

    fun clip(output: String, max: Int = 6000): String =
        if (output.length <= max) output else output.take(max / 2) +
            "\n… [${output.length - max} chars omitted] …\n" + output.takeLast(max / 2)
}