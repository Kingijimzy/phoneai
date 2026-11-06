package com.phoneai.app

/**
 * Shell hard blocks. Commands run directly with no approval tier; only what cannot be undone or would expose
 * the owner's keys is refused. Orion runs Android /system/bin/sh, so the checks target Android paths and
 * devices. Ultron's three Windows strings are kept as a harmless compatibility subset.
 */
internal object ShellPolicy {
    private val legacyWindows = listOf("format c:", "rmdir /s /q c:\\windows", "del /f /s /q c:\\windows")

    private val wipeTargets = setOf(
        "/", "/*", "/system", "/system/*", "/vendor", "/vendor/*", "/data", "/data/*",
        "/sdcard", "/sdcard/*", "/storage/emulated", "/storage/emulated/0", "/storage/emulated/0/*"
    )

    private val rawDeviceWrite = listOf(
        Regex("(?i)\\bdd\\b[^\\n;|&]*\\bof=/dev/(block|sd[a-z]|mmcblk|nvme|mapper)"),
        Regex("(?i)>\\s*/dev/(block/|sd[a-z]|mmcblk|nvme)"),
        Regex("(?i)\\bmkfs(\\.[a-z0-9]+)?\\b[^\\n;|&]*\\s/dev/"),
        Regex("(?i)\\b(wipefs|blkdiscard|shred)\\b[^\\n;|&]*/dev/")
    )
    private val forkBomb = Regex(":\\(\\)\\s*\\{")
    private val factoryReset = Regex("(?i)\\brecovery\\s+--wipe_(data|all)\\b")

    /** Places that hold Orion's API keys and tokens: app prefs, the app data directory, and keystore files. */
    private val keyStorage = listOf(
        Regex("(?i)shared_prefs"),
        Regex("(?i)/data/(data|user/\\d+)/com\\.phoneai\\.app"),
        Regex("(?i)/data/misc/(keystore|keychain)|\\bkeystore2?\\b|android_?keystore"),
        Regex("(?i)orion_api_credentials|apikeyvault")
    )

    private fun segments(command: String): List<String> =
        command.split(Regex("&&|\\|\\||[;|\\n]")).map { it.trim() }.filter { it.isNotEmpty() }

    private fun recursiveRootWipe(command: String): Boolean = segments(command).any { seg ->
        val words = seg.split(Regex("\\s+"))
        if (words.firstOrNull()?.removePrefix("\\") != "rm") return@any false
        val flags = words.drop(1).filter { it.startsWith("-") }
        val recursive = flags.any { it == "--recursive" || (!it.startsWith("--") && it.any { c -> c == 'r' || c == 'R' }) }
        recursive && words.drop(1).filter { !it.startsWith("-") }.any { arg ->
            val a = arg.trim('"', '\'')
            a in wipeTargets || a.trimEnd('/') in wipeTargets
        }
    }

    /** A short reason when the command must not run, or null when it may run. */
    fun blockReason(command: String): String? {
        val cmd = command.trim()
        val lower = cmd.lowercase()
        if (legacyWindows.any { lower.contains(it) }) return "wipes a Windows system path (Ultron compatibility list)"
        if (recursiveRootWipe(cmd)) return "recursively deletes the root, system, data or whole storage"
        if (rawDeviceWrite.any { it.containsMatchIn(cmd) }) return "writes raw to a block device"
        if (forkBomb.containsMatchIn(cmd)) return "is a fork bomb"
        if (factoryReset.containsMatchIn(cmd)) return "factory-resets the phone"
        if (keyStorage.any { it.containsMatchIn(cmd) }) return "touches key storage (API keys, shared prefs or keystore files)"
        return null
    }

    fun isIrreversible(command: String): Boolean = blockReason(command) != null

    fun blockMessage(command: String): String = "error: blocked because the command " + (blockReason(command) ?: "is not allowed")

    fun clip(output: String, max: Int = 6000): String =
        if (output.length <= max) output else output.take(max / 2) +
            "\n… [${output.length - max} chars omitted] …\n" + output.takeLast(max / 2)
}
