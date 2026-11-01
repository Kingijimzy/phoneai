package com.phoneai.app

/** Argument-vector policy for non-root Android utility commands. No shell parser or shell process. */
internal object OrionPythonShellPolicy {
    data class Command(val executable: String, val args: List<String>, val workingDirectory: String)
    private val commands = setOf("ls", "cat", "head", "tail", "grep", "wc", "date", "uname", "getprop", "df", "pwd")
    private val token = Regex("^[A-Za-z0-9_./:=,+@%-]{1,180}$")
    fun parse(json: String, workspacePath: String): Command? {
        if (json.length > 1200 || json.contains('\u0000')) return null
        val values = runCatching {
            val array = org.json.JSONArray(json)
            if (array.length() !in 1..12) return null
            (0 until array.length()).map { array.getString(it) }
        }.getOrNull() ?: return null
        val name = values.first()
        if (name !in commands) return null
        val args = values.drop(1)
        if (args.any { !token.matches(it) || (it.startsWith("-") && it.length > 40) }) return null
        val fileCommands = setOf("cat", "head", "tail", "grep", "wc")
        if (name in fileCommands) {
            val fileArgs = if (name == "grep") args.drop(1) else args
            if (fileArgs.isEmpty() || fileArgs.any { it.startsWith("/") || it.split('/').any { p -> p == ".." } }) return null
        }
        if (name == "ls" && args.any { it.startsWith("/") || it.split('/').any { p -> p == ".." } }) return null
        if (name == "pwd") return Command("pwd", emptyList(), workspacePath)
        return Command("/system/bin/$name", args, workspacePath)
    }
}
