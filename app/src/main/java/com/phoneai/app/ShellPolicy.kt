package com.phoneai.app

enum class ShellRisk { SAFE, BLOCKED }

/** Central hard-block policy for catastrophic device destruction and private key-store reads. */
internal object ShellPolicy {
    private val confirmedCommands = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun confirm(command: String) { confirmedCommands.add(command.trim()) }

    private fun normalized(command: String): String =
        command.lowercase().replace(Regex("""['"\\\\]"""), "")
            .replace(Regex("""\\s+"""), " ").trim()

    private fun isCatastrophicDelete(command: String): Boolean {
        val c = normalized(command)
        val rootDelete = Regex("""\\brm\\b[^;&|]*\\s(?:/|/\\*|/system|/vendor|/product|/boot|/recovery|/data|/metadata|/dev)\\b""")
        val explicitWipe = Regex("""\\b(factory\\s*reset|factory_reset|wipe[ ._-]*(data|cache|all|system)|format[ ._-]*(userdata|data|system)|erase[ ._-]*all)\\b""")
        val recoveryFastboot = Regex("""\\b(recovery\\s+--wipe|fastboot\\s+(erase|format|wipe))\\b""")
        val resetSettings = c.contains("settings put global device_provisioned 0") ||
            c.contains("settings put secure setup_wizard_finished 0")
        val forkBomb = c.contains(":(){:|:&};:") || c.contains("fork(){fork|fork&};fork")
        val rawDeviceFormat = Regex("""\\b(mkfs\\.[a-z0-9]+|wipefs|blkdiscard)\\b[^;&|]*\\b/dev/(block|mmcblk|sd[a-z])""")
        return rootDelete.containsMatchIn(c) || explicitWipe.containsMatchIn(c) ||
            recoveryFastboot.containsMatchIn(c) || resetSettings || forkBomb ||
            rawDeviceFormat.containsMatchIn(c)
    }

    private fun writesRawDevice(command: String): Boolean {
        val c = normalized(command)
        val device = """/dev/(?:block/|mmcblk\\d|sd[a-z]|nvme\\d|sda\\b)"""
        val destination = Regex("""(?:\\bof\\s*=|>+\\s*|\\b(?:cp|mv|tee|truncate)\\b[^;&|]*\\s)${device}""")
        val pythonWrite = Regex("""open\\([^)]*${device}[^)]*,\\s*(?:w|a|wb|ab|r\\+)""")
        val ddWrite = Regex("""\\bdd\\b""").containsMatchIn(c) &&
            Regex("""\\bof\\s*=""").containsMatchIn(c) && device.toRegex().containsMatchIn(c)
        return ddWrite || destination.containsMatchIn(c) || pythonWrite.containsMatchIn(c)
    }

    private fun readsPrivateKeyStorage(command: String): Boolean {
        val c = normalized(command)
        return listOf("/data/data/com.phoneai.app/shared_prefs/",
            "/data/user/0/com.phoneai.app/files", "/data/misc/keystore/",
            "orion_api_credentials").any(c::contains)
    }

    fun classify(command: String): ShellRisk {
        val exact = command.trim()
        if (exact.isBlank()) return ShellRisk.SAFE
        if (confirmedCommands.remove(exact)) return ShellRisk.SAFE
        return if (isCatastrophicDelete(exact) || writesRawDevice(exact) ||
            readsPrivateKeyStorage(exact)) ShellRisk.BLOCKED else ShellRisk.SAFE
    }

    fun clip(output: String, max: Int = 6000): String =
        if (output.length <= max) output else output.take(max / 2) +
            "\\n… [${output.length - max} chars omitted] …\\n" + output.takeLast(max / 2)
}
