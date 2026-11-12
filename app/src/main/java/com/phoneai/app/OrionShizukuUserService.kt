package com.phoneai.app

/**
 * Runs in Shizuku's shell/root identity. This service accepts only named, validated operations
 * and constructs every process argument locally. It never accepts a command line from the model.
 */
internal class OrionShizukuUserService : IOrionDeviceService.Stub() {
    override fun execute(operation: String, argument: String): String {
        return try {
            when (operation) {
            "read_brightness" -> runFixed("settings", "get", "system", "screen_brightness")
            "read_screen_timeout" -> runFixed("settings", "get", "system", "screen_off_timeout")
            "read_airplane_mode" -> runFixed("settings", "get", "global", "airplane_mode_on")
            "read_battery" -> runFixed("dumpsys", "battery")
            "list_user_packages" -> runFixed("cmd", "package", "list", "packages", "-3")
            "set_brightness" -> {
                val value = argument.toIntOrNull()?.takeIf { it in 0..255 }
                    ?: return "error: brightness must be an integer from 0 to 255"
                val changed = runFixed("settings", "put", "system", "screen_brightness", value.toString())
                if (changed.startsWith("error", true)) changed else {
                    val actual = runFixed("settings", "get", "system", "screen_brightness").trim()
                    if (actual == value.toString()) "verified: screen brightness=$actual"
                    else "error: brightness write was not verified (read-back=$actual)"
                }
            }
            "set_screen_timeout" -> {
                val value = argument.toLongOrNull()?.takeIf { it in 15_000L..1_800_000L }
                    ?: return "error: screen timeout must be 15000..1800000 milliseconds"
                val changed = runFixed("settings", "put", "system", "screen_off_timeout", value.toString())
                if (changed.startsWith("error", true)) changed else {
                    val actual = runFixed("settings", "get", "system", "screen_off_timeout").trim()
                    if (actual == value.toString()) "verified: screen timeout=$actual ms"
                    else "error: screen timeout write was not verified (read-back=$actual)"
                }
            }
            else -> "error: unsupported Shizuku operation"
            }
        } catch (failure: Throwable) {
            "error: Shizuku operation failed (" + failure.javaClass.simpleName + ")"
        }
    }

    private fun runFixed(vararg command: String): String {
        val process = try { ProcessBuilder(*command).redirectErrorStream(true).start() }
        catch (failure: Exception) { return "error: command could not start (" + failure.javaClass.simpleName + ")" }
        val output = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().use { input ->
                    val buffer = CharArray(512)
                    while (output.length < 8_000) {
                        val count = input.read(buffer, 0, minOf(buffer.size, 8_000 - output.length))
                        if (count <= 0) break
                        synchronized(output) { output.append(buffer, 0, count) }
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        val finished = process.waitFor(3_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroy()
            process.destroyForcibly()
            reader.join(200)
            return "error: Shizuku command timed out"
        }
        reader.join(250)
        val text = synchronized(output) { output.toString().trim() }.take(8_000)
        if (process.exitValue() != 0) "error: command exited " + process.exitValue() + ": " + text
        else text.ifBlank { "ok" }
    }
}