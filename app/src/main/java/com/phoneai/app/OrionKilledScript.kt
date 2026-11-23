package com.phoneai.app

/** When a script is killed, the answer is a different script, not the same one again. */
internal object OrionKilledScript {
    private val markers = listOf("exit code 137", "exit 137", "sigkill", "killed", "timed out", "timeout expired", "memoryerror", "out of memory", "terminated")
    private val scriptTools = setOf("python_exec", "dev_python", "dev_shell", "shell", "devspace", "termux_command")

    fun wasKilled(tool: String, result: String): Boolean =
        tool in scriptTools && result.take(4000).lowercase().let { r -> markers.any { it in r } }

    fun hint(tool: String, result: String): String =
        if (!wasKilled(tool, result)) "" else
            "\nKILLED: that script was stopped (time or memory). Do not run it again unchanged. Write a replacement that works in smaller chunks, " +
                "saves progress to a file as it goes and resumes from it; if you will need it again, keep it with extension_forge."
}
