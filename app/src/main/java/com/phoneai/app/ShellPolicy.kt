package com.phoneai.app

/** Ultron-compatible legacy Windows command guard. Android/Linux commands otherwise pass through. */
internal object ShellPolicy {
    private val irreversibleWindowsCommands = listOf(
        "format c:",
        "rmdir /s /q c:\\windows",
        "del /f /s /q c:\\windows"
    )

    fun isIrreversible(command: String): Boolean {
        val normalized = command.lowercase()
        return irreversibleWindowsCommands.any { normalized.contains(it) }
    }

    fun clip(output: String, max: Int = 6000): String =
        if (output.length <= max) output else output.take(max / 2) +
            "\n… [${output.length - max} chars omitted] …\n" + output.takeLast(max / 2)
}
