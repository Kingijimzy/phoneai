package com.phoneai.app

enum class ShellRisk { SAFE, BLOCKED }

/**
 * Shell execution policy. Destructive file/system operations are sent to the shared one-time
 * confirmation surface; only private credential-store reads and process-fork bombs are hard-blocked.
 * Inspect both the original command and a conservative dequoted form so simple quoting
 * and escaping cannot trivially bypass the guard.
 */
internal object ShellPolicy {
    private val rootOrSystemWipe = listOf(
        Regex("""(?is)\b(?:rm|rmdir)\b[^;&|]*(?:\s|^)/(?:\s|$|\*|system(?:/|\s|$)|vendor(?:/|\s|$)|product(?:/|\s|$)|odm(?:/|\s|$)|boot(?:/|\s|$)|recovery(?:/|\s|$))"""),
        Regex("""(?is)\bfind\s+/(?:\s|$)[^;&|]*\s-(?:delete|exec|ok)\b""")
    )
    private val factoryResetProtection = listOf(
        Regex("""(?is)\b(?:factory.reset|wipe.data|wipe.cache|format.userdata|erase.all|factory_reset|--wipe\s+(?:all|data))"""),
        Regex("""(?is)\bformat\s+/(?:data|system|vendor|product|odm|boot|cache|sdcard|storage)(?:/|\s|$)"""),
        Regex("""(?is)\brecovery\s+--wipe\s+(?:data|cache|all)\b"""),
        Regex("""(?is)\b(?:fastboot)\s+(?:erase|format|wipe)\b"""),
        Regex("""(?is)\bsettings\s+put\s+(?:global|secure|system)\s+(?:device_provisioned|setup_wizard).*0\b""")
    )
    private val rawBlockDeviceWrite = listOf(
        Regex("""(?is)\bof\s*=\s*[^;&|]*?/dev/(?:block(?:/|\b)|sd[a-z]\b|nvme\d+n\d+\b|mmcblk\d+\b|loop\d+\b|dm-\d+\b)"""),
        Regex("""(?is)>{1,2}\s*[^;&|]*?/dev/(?:block(?:/|\b)|sd[a-z]\b|nvme\d+n\d+\b|mmcblk\d+\b|loop\d+\b|dm-\d+\b)"""),
        Regex("""(?is)\b(?:mkfs(?:\.\w+)?|wipefs|blkdiscard)\b[^;&|]*?/dev/(?:block(?:/|\b)|sd[a-z]\b|nvme\d+n\d+\b|mmcblk\d+\b|loop\d+\b|dm-\d+\b)"""),
        Regex("""(?is)\b(?:cp|mv|tee|truncate)\b[^;&|]*\s/dev/(?:block(?:/|\b)|sd[a-z]\b|nvme\d+n\d+\b|mmcblk\d+\b|loop\d+\b|dm-\d+\b)"""),
        // Inline interpreters can write raw device nodes without shell redirection.
        Regex("""(?is)\b(?:python(?:3)?|perl|ruby|node)\s+(?:-[ce]|-e)\s+[^;&|]*?/dev/(?:block(?:/|\b)|sd[a-z]\b|nvme\d+n\d+\b|mmcblk\d+\b|loop\d+\b|dm-\d+\b)""")
    )
    private val remotePipeToShell = Regex("""(?is)\b(?:curl|wget)\b[^;&|]*\|\s*(?:sudo\s+)?(?:sh|bash|zsh)\b""")
    /** Prompt-injected shell reads of API keys, app prefs or keystore files could be exfiltrated. */
    private val keyStorage = listOf(
        Regex("(?i)shared_prefs"),
        Regex("(?i)/data/(data|user/\\d+)/com\\.phoneai\\.app"),
        Regex("(?i)/data/misc/(keystore|keychain)|android_?keystore"),
        Regex("(?i)orion_api_credentials|apikeyvault")
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
        if (forms.any { form -> keyStorage.any { it.containsMatchIn(form) } }) return ShellRisk.BLOCKED
        if (forms.any { form -> forkBomb.any { it.containsMatchIn(form) } }) return ShellRisk.BLOCKED
        // rm/rmdir/find -delete, filesystem formats, factory-reset and raw-device writes are
        // destructive actions: they are not silently blocked; the dispatcher asks once first.
        return ShellRisk.SAFE
    }

    fun clip(output: String, max: Int = 6000): String =
        if (output.length <= max) output else output.take(max / 2) +
            "\n… [${output.length - max} chars omitted] …\n" + output.takeLast(max / 2)
}
