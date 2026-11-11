package com.phoneai.app

enum class ShellRisk { SAFE, BLOCKED }

internal object ShellPolicy {
    private val confirmedCommands = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun confirm(command: String) { confirmedCommands.add(command.trim()) }

    private fun normalized(command: String): String =
        command.lowercase().replace(Regex("""["'\\]"""), "")
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""\s*([|;&])\s*"""), " $1 ").trim()

    private fun isCatastrophicDelete(command: String): Boolean {
        val c = normalized(command)
        val rootDelete = Regex("""\brm\b[^;&|]*\s/(?:\s|$|\*|system(?:/|$)|vendor(?:/|$)|product(?:/|$)|boot(?:/|$)|recovery(?:/|$)|data(?:/|$)|metadata(?:/|$)|dev(?:/|$))""")
        val rootFindDelete = Regex("""\bfind\b\s+/(?:\s|$|\*|system(?:/|$)|data(?:/|$)|vendor(?:/|$))[^;&|]*\s-delete\b""")
        val explicitWipe = Regex("""\bfactory\s+reset\b|\bfactory_reset\b|\bwipe[ ._-]*(?:data|cache|all|system)\b|\bformat[ ._-]*(?:userdata|data|system)\b|\bformat\s+/(?:data|system|userdata|metadata)\b|\berase[ ._-]*all\b""")
        val recoveryFastboot = Regex("""\brecovery\s+--wipe\b|\bfastboot\s+(?:erase|format|wipe)\b""")
        val resetSettings = c.contains("settings put global device_provisioned 0") ||
            c.contains("settings put secure setup_wizard_finished 0")
        val colonForkBomb = Regex(""":\s*\(\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;\s*:""").containsMatchIn(c)
        val namedForkBomb = Regex("""\bfork\s*\(\s*\)\s*\{\s*fork\s*\|\s*fork\s*&\s*\}\s*;\s*fork\b""").containsMatchIn(c)
        val forkBomb = colonForkBomb || namedForkBomb
        val rawDeviceFormat = Regex("""\b(?:mkfs\.[a-z0-9]+|wipefs|blkdiscard)\b[^;&|]*(?:^|\s)/dev/(?:block/|mmcblk|sd[a-z])""")
        return rootDelete.containsMatchIn(c) || rootFindDelete.containsMatchIn(c) || explicitWipe.containsMatchIn(c) ||
            recoveryFastboot.containsMatchIn(c) || resetSettings || forkBomb ||
            rawDeviceFormat.containsMatchIn(c)
    }

    private fun writesRawDevice(command: String): Boolean {
        val c = normalized(command)
        val device = Regex("""/dev/(?:block/|mmcblk\d+|sd[a-z]|nvme\d+n\d+|sda\b)""")
        // Only the dd output target is a write; reading a block device into a workspace backup is allowed.
        val ddWrite = Regex("""\bdd\b[^;&|]*\bof\s*=\s*/dev/(?:block/|mmcblk\d+|sd[a-z]|nvme\d+n\d+|sda\b)""").containsMatchIn(c)
        val redirectedWrite = Regex("""(?:>{1,2}\s*|\btee\s+)""").containsMatchIn(c) &&
            device.containsMatchIn(c)
        val copyWrite = Regex("""\b(?:cp|mv|truncate)\b[^;&|]*\s""").containsMatchIn(c) &&
            device.containsMatchIn(c)
        val pythonWrite = Regex("""open\([^)]*,\s*(?:w|a|wb|ab|r\+)""").containsMatchIn(c) &&
            device.containsMatchIn(c)
        return ddWrite || redirectedWrite || copyWrite || pythonWrite
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
            "\n… [" + (output.length - max) + " chars omitted] …\n" + output.takeLast(max / 2)
}
