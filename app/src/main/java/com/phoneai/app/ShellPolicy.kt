package com.phoneai.app

enum class ShellRisk { SAFE, BLOCKED }

/**
 * Owner-authorized shell policy. Ordinary workspace and system commands execute without
 * per-command approval; only destructive device-level primitives are hard-blocked.
 * Inspect both the original command and a conservative dequoted form so simple quoting
 * and escaping cannot trivially bypass the guard.
 */
internal object ShellPolicy {
    private val rootOrSystemWipe = listOf(
        Regex("""(?is)\b(?:rm|rmdir)\b[^;&|]*(?:\s|^)/(?:\s|$|\*|system(?:/|\s|$)|vendor(?:/|\s|$)|product(?:/|\s|$)|odm(?:/|\s|$)|boot(?:/|\s|$)|recovery(?:/|\s|$))"""),
        Regex("""(?is)\bfind\s+/(?:\s|$)[^;&|]*\s-(?:delete|exec|ok)\b""")
    )
    private val rawBlockDeviceWrite = listOf(
        Regex("""(?is)\bof\s*=\s*[^;&|]*?/dev/(?:block(?:/|\b)|sd[a-z]\b|nvme\d+n\d+\b|mmcblk\d+\b|loop\d+\b|dm-\d+\b)"""),
        Regex("""(?is)>{1,2}\s*[^;&|]*?/dev/(?:block(?:/|\b)|sd[a-z]\b|nvme\d+n\d+\b|mmcblk\d+\b|loop\d+\b|dm-\d+\b)"""),
        Regex("""(?is)\b(?:mkfs(?:\.\w+)?|wipefs|blkdiscard)\b[^;&|]*?/dev/(?:block(?:/|\b)|sd[a-z]\b|nvme\d+n\d+\b|mmcblk\d+\b|loop\d+\b|dm-\d+\b)"""),
        Regex("""(?is)\b(?:cp|mv|tee|truncate)\b[^;&|]*\s/dev/(?:block(?:/|\b)|sd[a-z]\b|nvme\d+n\d+\b|mmcblk\d+\b|loop\d+\b|dm-\d+\b)""")
    )
    private val forkBomb = listOf(
        Regex("""(?s):\s*\(\s*\)\s*\{[^}]*\}"""),
        Regex("""(?s)\b(?:fork|bomb)\s*\(\s*\)\s*\{[^}]*\}""")
    )

    private fun inspectionForms(command: String): List<String> {
        val raw = command.trim()
        // This is not a shell parser. It is an additional conservative inspection form;
        // actual execution remains the platform shell and the command is never rewritten.
        val dequoted = raw.replace("'", "").replace("\"", "").replace("`", "")
            .replace("\\", "").lowercase()
            .replace(Regex("\\s+"), " ")
        return listOf(raw, dequoted).distinct()
    }

    fun classify(command: String): ShellRisk {
        val forms = inspectionForms(command)
        if (forms.any { form -> rootOrSystemWipe.any { it.containsMatchIn(form) } }) return ShellRisk.BLOCKED
        if (forms.any { form -> rawBlockDeviceWrite.any { it.containsMatchIn(form) } }) return ShellRisk.BLOCKED
        if (forms.any { form -> forkBomb.any { it.containsMatchIn(form) } }) return ShellRisk.BLOCKED
        return ShellRisk.SAFE
    }

    fun clip(output: String, max: Int = 6000): String =
        if (output.length <= max) output else output.take(max / 2) +
            "\n… [${output.length - max} chars omitted] …\n" + output.takeLast(max / 2)
}
