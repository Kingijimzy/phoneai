package com.phoneai.app

/**
 * Parses explicit argv arrays for Android-host commands.
 * Shell expansion is opt-in: callers may invoke /system/bin/sh -c themselves.
 * Processes remain under the Android app UID and platform sandbox.
 */
internal object OrionPythonShellPolicy {
    data class Command(val executable: String, val args: List<String>, val workingDirectory: String)

    fun parse(json: String, workspacePath: String): Command? {
        if (json.length > 128_000 || json.contains('\u0000')) return null
        val values = runCatching {
            val array = org.json.JSONArray(json)
            if (array.length() !in 1..256) return null
            (0 until array.length()).map { index ->
                if (array.isNull(index)) return null
                array.getString(index)
            }
        }.getOrNull() ?: return null
        val name = values.firstOrNull()?.trim().orEmpty()
        if (name.isBlank() || name.contains('\u0000') || values.any { it.contains('\u0000') }) return null
        val executable = when {
            name.startsWith("/") -> name
            name.contains("/") -> java.io.File(workspacePath, name).path
            else -> "/system/bin/$name"
        }
        return Command(executable, values.drop(1), workspacePath)
    }
}
