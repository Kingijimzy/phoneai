package com.phoneai.app

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

/**
 * Full Python execution uses CPython installed in Orion's pinned Alpine/PRoot guest.
 * Generated extensions intentionally remain on the restricted Chaquopy data-transform runtime.
 *
 * PRoot runs under the app UID (it is not a kernel security boundary). Only the dedicated,
 * quota-limited Python workspace is bind-mounted; Orion databases, preferences and credentials
 * are never mounted into the guest.
 */
internal object OrionPythonRuntime {
    private const val SCRIPT_NAME = "orion_main.py"
    private const val MAX_OUTPUT_CHARS = 100_000
    private const val EXECUTION_TIMEOUT_MS = 100_000L
    private const val FULL_EXECUTION_TIMEOUT_MS = 900_000L

    @Synchronized
    fun execute(context: Context, code: String, confirm: (String) -> Boolean = { false }, stopped: () -> Boolean = { false }, onOutput: (String) -> Unit = {}, toolInvoker: ((String, org.json.JSONObject) -> String)? = null): String {
        val maxChars = PythonExecutionPolicy.maxCodeChars(context)
        if (code.isBlank()) return "error: Python code is required."
        if (code.length > maxChars) return "error: Python code exceeds the active profile limit of $maxChars characters."
        if (code.contains('\u0000')) return "error: Python code contains a forbidden NUL character."

        if (stopped()) return "error: Python execution cancelled before launch."
        val app = context.applicationContext
        val workspace = OrionPythonWorkspace(app)
        val workspaceRoot = when (workspace.rootStatus()) {
            OrionPythonWorkspace.RootStatus.READY -> File(app.filesDir, "orion-python/workspace")
            OrionPythonWorkspace.RootStatus.MISSING -> {
                if (workspace.ensureRootForRepair() == null) return "error: Python workspace could not be safely initialized."
                File(app.filesDir, "orion-python/workspace")
            }
            OrionPythonWorkspace.RootStatus.UNSAFE -> return "error: Python workspace path is unsafe; refusing execution."
        }
        val saved = workspace.write(SCRIPT_NAME, code)
        if (!saved.startsWith("saved ")) return saved

        val installer = LinuxRuntimeInstaller(app)
        val manifest = LinuxRuntimePolicy.productionManifest
        if (!installer.isReady(manifest)) {
            val installed = installer.install(manifest)
            if (installed.isFailure) return runBundled(app, workspaceRoot, code, "Linux runtime install failed: ${(installed.exceptionOrNull()?.message ?: "unknown error").take(120)}")
        }

        // Package installation is persistent in the app-private Alpine rootfs. The owner's
        // explicit python_exec request authorizes this pinned runtime/package bootstrap.
        val probe = installer.execute(manifest, "python3 --version", 8_000L, workspaceHostPath = workspaceRoot.absolutePath, shouldStop = stopped)
        if (!probe.startsWith("exit=0") || !probe.contains("Python 3.")) {
            // The acceptance workload requires full CPython and its standard library only.
            // py3-pip and build-base are unnecessary, large dependency trees on a 4–8 GB phone
            // and were the source of avoidable package-install failures. apk add refreshes indexes
            // as needed; do not run a separate apk update that doubles downloads.
            val install = installer.execute(
                manifest, "apk add --no-cache python3 || { rc=\$?; echo \"CPYTHON_INSTALL_DIAGNOSTICS\"; echo \"--- filesystem ---\"; df -h / /var/cache/apk 2>&1; echo \"--- inodes ---\"; df -i / 2>&1; echo \"--- package state ---\"; apk info -e python3 2>&1; echo \"--- repositories ---\"; cat /etc/apk/repositories 2>&1; exit \$rc; }",
                180_000L, onOutput = onOutput, workspaceHostPath = workspaceRoot.absolutePath, shouldStop = stopped
            )
            if (!install.startsWith("exit=0")) return runBundled(app, workspaceRoot, code, "CPython install in the Linux runtime failed: " + install.take(160).replace("\n", " "))
            val verified = installer.execute(manifest, "python3 --version && python3 -c 'import sys, platform; print(sys.executable); print(platform.machine()); print(sys.prefix)'", 12_000L, workspaceHostPath = workspaceRoot.absolutePath, shouldStop = stopped)
            if (!verified.startsWith("exit=0") || !verified.contains("Python 3.")) {
                return runBundled(app, workspaceRoot, code, "Linux CPython verification failed")
            }
        }

        // CPython can write directly through the bind mount, bypassing OrionPythonWorkspace.write().
        // Refuse to start with an already exhausted/unsafe workspace and audit it again after exit.
        when (workspace.quotaStatus()) {
            "ok" -> Unit
            "exceeded" -> return "error: Python workspace is already over its 1 GiB quota; remove files before execution."
            else -> return "error: Python workspace failed its integrity check; refusing execution."
        }

        val phoneApi = OrionPythonPhoneApi(app, readOnly = ToolPermissions.level(app) == "read_only", toolInvoker = toolInvoker)
        val bridge = try { OrionPythonPhoneBridge(phoneApi) }
        catch (failure: Exception) {
            return "error: could not start the private Python-to-Android bridge (${failure.javaClass.simpleName})."
        }
        var quotaExceededDuringRun = false
        val result = try {
            val full = OrionOwnerDialSettings.read(app).pythonProfile == OrionPythonProfile.FULL
            val bootstrap = OrionPythonPhoneBridge.bootstrapCommand(full)
            val limits = if (full) "ulimit -c 0; ulimit -t 9000; ulimit -f 8388608; ulimit -n 2048; ulimit -u 1024" else "ulimit -c 0; ulimit -t 90; ulimit -f 4096; ulimit -n 64; ulimit -u 32"
            installer.execute(
                manifest,
                "$limits; cd /workspace && exec python3 -I -c '$bootstrap'",
                if (full) FULL_EXECUTION_TIMEOUT_MS else EXECUTION_TIMEOUT_MS,
                onOutput = onOutput,
                workspaceHostPath = workspaceRoot.absolutePath,
                shouldStop = {
                    if (stopped()) true
                    else if (workspace.quotaStatus() == "exceeded") {
                        quotaExceededDuringRun = true
                        true
                    } else false
                },
                extraEnvironment = bridge.environment()
            )
        } finally {
            bridge.close()
        }
        val quotaAfter = workspace.quotaStatus()
        if (quotaAfter == "unsafe") return "error: Python finished, but workspace integrity validation failed; inspect the workspace before using its files.\n${result.take(MAX_OUTPUT_CHARS)}"
        if (quotaExceededDuringRun || quotaAfter == "exceeded") return "error: Python exceeded the 1 GiB workspace quota and was stopped. Further execution is blocked until the workspace is cleaned.\n${result.take(MAX_OUTPUT_CHARS)}"
        return result.take(MAX_OUTPUT_CHARS)
    }

    /**
     * Fallback that always works: the CPython bundled in the APK (stdlib, urllib/ssl, sqlite, requests,
     * beautifulsoup4), running in the same persistent workspace. Used when the Linux guest cannot start.
     */
    private fun runBundled(app: Context, workspaceRoot: File, code: String, why: String): String = try {
        if (!Python.isStarted()) Python.start(AndroidPlatform(app))
        val r = Python.getInstance().getModule("orion_full").callAttr("run_full", code, workspaceRoot.absolutePath, 120).toString()
        "[ran in Orion's built-in Python (workspace: ${workspaceRoot.name}); $why]\n" + r.take(MAX_OUTPUT_CHARS)
    } catch (e: Exception) { "error: built-in Python failed too (${(e.message ?: e.javaClass.simpleName).take(160)}); $why" }

    /** Generated extensions remain restricted data transforms; they receive no host capabilities. */
    @Synchronized
    fun runExtension(context: Context, source: String, inputJson: String): String = try {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        Python.getInstance().getModule("orion_exec").callAttr("run_extension", source, inputJson).toString()
    } catch (e: Exception) {
        org.json.JSONObject().put("ok", false)
            .put("error", "bundled Python failed safely (${(e.message ?: e.javaClass.simpleName).take(180)})").toString()
    }
}
