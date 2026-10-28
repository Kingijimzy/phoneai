package com.phoneai.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Runs commands in Orion's private workspace (built-in shell) or in the user's Termux (full Linux tools). */
object ShellRunner {
    private const val TERMUX_PKG = "com.termux"
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"
    const val ACTION_RESULT = "com.phoneai.app.TERMUX_RESULT"
    private val ids = AtomicInteger(1000)
    internal class Pending { val latch = CountDownLatch(1); @Volatile var text = "" }
    internal val pending = ConcurrentHashMap<Int, Pending>()

    fun workspace(c: Context): File = File(c.filesDir, "workspace").also { it.mkdirs() }

    private fun currentDirectory(c: Context): File {
        val root = workspace(c).canonicalFile
        val saved = c.getSharedPreferences("orion_builtin_shell", Context.MODE_PRIVATE)
            .getString("cwd", root.path) ?: root.path
        val candidate = runCatching { File(saved).canonicalFile }.getOrNull()
        return if (candidate != null && candidate.isDirectory &&
            (candidate.path == root.path || candidate.path.startsWith(root.path + File.separator))) candidate else root
    }

    private fun changeDirectory(c: Context, raw: String): String {
        val root = workspace(c).canonicalFile
        val current = currentDirectory(c)
        val arg = raw.trim().removeSurrounding("\"").removeSurrounding("'")
        if (arg.isBlank() || arg == "~") {
            c.getSharedPreferences("orion_builtin_shell", Context.MODE_PRIVATE).edit().putString("cwd", root.path).apply()
            return "cwd: workspace/"
        }
        val candidate = runCatching { (if (File(arg).isAbsolute) File(arg) else File(current, arg)).canonicalFile }.getOrNull()
            ?: return "error: invalid directory"
        if (candidate.path != root.path && !candidate.path.startsWith(root.path + File.separator))
            return "error: directory must remain inside Orion's private workspace"
        if (!candidate.isDirectory) return "error: no such workspace directory"
        c.getSharedPreferences("orion_builtin_shell", Context.MODE_PRIVATE).edit().putString("cwd", candidate.path).apply()
        val relative = candidate.relativeTo(root).path
        return "cwd: workspace/" + if (relative == ".") "" else relative
    }

    /** Resolves a path inside the workspace, refusing anything that escapes it. */
    fun safePath(c: Context, rel: String): File? {
        if (rel.isBlank() || rel.startsWith("/") || rel.contains("\u0000")) return null
        val root = workspace(c).canonicalFile
        val f = File(root, rel).canonicalFile
        return if (f.path == root.path || f.path.startsWith(root.path + File.separator)) f else null
    }

    fun termuxInstalled(c: Context): Boolean = try { c.packageManager.getPackageInfo(TERMUX_PKG, 0); true } catch (_: Exception) { false }

    fun runBuiltin(c: Context, command: String, timeoutSec: Long = 20): String {
        val normalized = command.trim()
        if (normalized.isEmpty()) return "error: command is required"
        if (normalized.length > 2000) return "error: command exceeds the 2,000 character limit"
        // Defense in depth: callers must still obtain approval for ASK-class commands.
        if (ShellPolicy.classify(normalized) == ShellRisk.BLOCKED) {
            return "error: blocked by Orion shell safety policy (destructive, privileged, or sensitive-path command)"
        }
        if (normalized == "cd" || normalized.startsWith("cd ")) {
            return changeDirectory(c, normalized.removePrefix("cd").trim())
        }
        if (normalized == "pwd") {
            val root = workspace(c).canonicalFile
            val relative = currentDirectory(c).relativeTo(root).path
            return "workspace/" + if (relative == ".") "" else relative
        }
        BuiltinCommands.run(c, normalized)?.let { return ShellPolicy.clip(it) }
        return try {
            val p = ProcessBuilder("/system/bin/sh", "-c", normalized).directory(currentDirectory(c)).redirectErrorStream(true).start()
            val out = StringBuffer()
            val reader = Thread { try { p.inputStream.bufferedReader().use { r -> val buf = CharArray(2048); while (true) { val n = r.read(buf); if (n < 0) break; synchronized(out) { if (out.length < 20000) out.append(buf, 0, n) } } } } catch (_: Exception) { } }
            reader.start()
            if (!p.waitFor(timeoutSec.coerceIn(1, 30), TimeUnit.SECONDS)) { p.destroyForcibly(); reader.join(500); return ShellPolicy.clip(out.toString()) + "\n[timed out after ${timeoutSec}s and was stopped]" }
            reader.join(1000)
            val text = ShellPolicy.clip(out.toString().trimEnd())
            (if (p.exitValue() == 0) "" else "exit ${p.exitValue()}\n") + text.ifBlank { "(no output)" }
        } catch (e: Exception) { "error: could not run the command (" + (e.message ?: e.javaClass.simpleName).take(120) + ")" }
    }

    fun runTermux(c: Context, command: String, timeoutSec: Long = 60): String {
        val readiness = TermuxBridgeRunner.readiness(c)
        if (readiness != null) {
            return "error: $readiness Open Orion's Termux Bridge settings and complete the listed setup before using where=termux."
        }
        val normalized = command.trim()
        if (normalized.isEmpty()) return "error: command is required"
        if (normalized.length > 2000) return "error: command exceeds the 2,000 character limit"
        if (ShellPolicy.classify(normalized) == ShellRisk.BLOCKED) {
            return "error: blocked by Orion shell safety policy (destructive, privileged, or sensitive-path command)"
        }
        val effectiveTimeout = maxOf(timeoutSec, ShellExecutionRouting.commandTimeoutMs(normalized) / 1000L)
            .coerceIn(1L, 180L)
        val id = ids.incrementAndGet()
        val box = Pending(); pending[id] = box
        return try {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val cb = PendingIntent.getBroadcast(c, id, Intent(c, ShellResultReceiver::class.java).setAction(ACTION_RESULT).putExtra("id", id), flags)
            val termuxTimeout = "/data/data/com.termux/files/usr/bin/timeout"
            val i = Intent("com.termux.RUN_COMMAND").setComponent(ComponentName(TERMUX_PKG, "com.termux.app.RunCommandService"))
                .putExtra("com.termux.RUN_COMMAND_PATH", termuxTimeout)
                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("${effectiveTimeout}s", TERMUX_BASH, "-lc", normalized))
                .putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME)
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", cb)
            try { c.startService(i) } catch (_: Exception) { c.startForegroundService(i) }
            if (!box.latch.await(effectiveTimeout + 5L, TimeUnit.SECONDS)) "error: Termux did not answer within ${effectiveTimeout + 5L}s. Check that Termux has allow-external-apps=true and that Orion has the 'Run commands in Termux environment' permission."
            else box.text
        } catch (e: SecurityException) {
            "error: Android refused the Termux call. Grant Orion the permission 'Run commands in Termux environment' (App info > Permissions) and set allow-external-apps=true in ~/.termux/termux.properties."
        } catch (e: Exception) { "error: could not reach Termux (" + (e.message ?: e.javaClass.simpleName).take(120) + ")" }
        finally { pending.remove(id) }
    }

    fun run(c: Context, command: String, where: String): String =
        when (ShellExecutionRouting.backend(where)) {
            ShellExecutionRouting.Backend.BUILTIN -> runBuiltin(c, command)
            ShellExecutionRouting.Backend.TERMUX -> runTermux(c, command)
            ShellExecutionRouting.Backend.LINUX -> {
                val manifest = LinuxRuntimePolicy.productionManifest
                val runtime = LinuxRuntimeInstaller(c.applicationContext)
                if (!runtime.isReady(manifest)) {
                    val installed = runtime.install(manifest)
                    if (installed.isFailure) {
                        "error: Alpine runtime is not ready: ${installed.exceptionOrNull()?.message ?: "installation failed"}. No fallback to Android shell was attempted."
                    } else if (!runtime.isReady(manifest)) {
                        "error: Alpine installation finished but readiness checks failed. No fallback to Android shell was attempted."
                    } else {
                        runtime.execute(manifest, command, ShellExecutionRouting.commandTimeoutMs(command))
                    }
                } else runtime.execute(manifest, command, ShellExecutionRouting.commandTimeoutMs(command))
            }
            ShellExecutionRouting.Backend.INVALID -> "error: unknown shell backend. Use where=builtin, where=linux, or where=termux."
        }


    fun writeFile(c: Context, rel: String, content: String): String {
        val f = safePath(c, rel) ?: return "error: path must be relative and inside Orion's workspace"
        if (content.length > 200_000) return "error: file too large (max 200 KB)"
        return try { f.parentFile?.mkdirs(); f.writeText(content); "wrote ${content.length} chars to workspace/$rel" } catch (e: Exception) { "error: could not write (" + (e.message ?: "").take(100) + ")" }
    }

    fun readFile(c: Context, rel: String): String {
        val f = safePath(c, rel) ?: return "error: path must be relative and inside Orion's workspace"
        if (!f.isFile) return "error: no such file in the workspace"
        return try { ShellPolicy.clip(f.readText(), 8000) } catch (e: Exception) { "error: could not read (" + (e.message ?: "").take(100) + ")" }
    }
}

/** Receives Termux's stdout/stderr/exit code for a RUN_COMMAND call. */
class ShellResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1)
        val box = ShellRunner.pending[id] ?: return
        val extras = intent.extras
        val r = intent.getBundleExtra("com.termux.app.extra.PLUGIN_RESULT_BUNDLE")
            ?: intent.getBundleExtra("result")
            ?: extras?.keySet()?.mapNotNull { key -> extras.get(key) as? android.os.Bundle }
                ?.firstOrNull { it.containsKey("stdout") || it.containsKey("exitCode") || it.containsKey("errmsg") }
        box.text = if (r == null) "error: Termux returned no readable result bundle; outcome is unverified."
        else {
            val out = r.getString("stdout").orEmpty()
            val err = r.getString("stderr").orEmpty()
            val code = if (r.containsKey("exitCode")) r.getInt("exitCode") else Int.MIN_VALUE
            val termuxError = r.getInt("err", -1)
            val errmsg = r.getString("errmsg").orEmpty()
            when {
                termuxError != -1 -> "error: TERMUX SERVICE ERROR" + if (errmsg.isBlank()) "" else "\\n" + errmsg.take(1000)
                code == Int.MIN_VALUE -> "error: Termux returned output without an exit status; outcome is unverified."
                else -> (if (code == 0) "EXIT 0 · SUCCESS\\n" else "error: EXIT $code · FAILED\\n") +
                    ShellPolicy.clip((out + (if (err.isNotBlank()) "\\nSTDERR\\n" + err else "")).trimEnd()).ifBlank { "(no output)" }
            }
        }
        box.latch.countDown()
    }
}
