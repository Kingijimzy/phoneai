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
    private const val MAX_OUTPUT_CHARS = 5_200
    private const val EXECUTION_TIMEOUT_MS = 100_000L

    @Synchronized
    fun execute(context: Context, code: String, confirm: (String) -> Boolean = { false }, stopped: () -> Boolean = { false }): String {
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
            if (!confirm("Install or repair Orion's pinned Alpine Linux runtime before running full CPython?")) {
                return "Python script saved to workspace/$SCRIPT_NAME. Full CPython needs the Alpine runtime; installation was not approved."
            }
            val installed = installer.install(manifest)
            if (installed.isFailure) return "error: Alpine runtime installation failed: ${(installed.exceptionOrNull()?.message ?: "unknown error").take(220)}"
        }

        // Package installation is persistent in the app-private Alpine rootfs and requires
        // separate approval because it downloads executable code from Alpine repositories.
        val probe = installer.execute(manifest, "python3 --version", 8_000L, workspaceHostPath = workspaceRoot.absolutePath, shouldStop = stopped)
        if (!probe.startsWith("exit=0") || !probe.contains("Python 3.")) {
            if (!confirm("Install CPython 3, pip and the Alpine build tools into Orion's persistent Linux runtime? This downloads packages from Alpine repositories.")) {
                return "Python script saved. CPython is not installed in Alpine; approve package installation or install python3 py3-pip from Orion Terminal."
            }
            val install = installer.execute(
                manifest, "apk update && apk add --no-cache python3 py3-pip build-base",
                180_000L, workspaceHostPath = workspaceRoot.absolutePath, shouldStop = stopped
            )
            if (!install.startsWith("exit=0")) return "error: CPython package installation failed.\n${install.take(MAX_OUTPUT_CHARS)}"
        }

        // CPython can write directly through the bind mount, bypassing OrionPythonWorkspace.write().
        // Refuse to start with an already exhausted/unsafe workspace and audit it again after exit.
        when (workspace.quotaStatus()) {
            "ok" -> Unit
            "exceeded" -> return "error: Python workspace is already over its 20 MiB quota; remove files before execution."
            else -> return "error: Python workspace failed its integrity check; refusing execution."
        }

        val phoneApi = OrionPythonPhoneApi(app, confirm, readOnly = ToolPermissions.level(app) == "read_only")
        val bridge = try { OrionPythonPhoneBridge(phoneApi) }
        catch (failure: Exception) {
            return "error: could not start the private Python-to-Android bridge (${failure.javaClass.simpleName})."
        }
        val result = try {
            val bootstrap = OrionPythonPhoneBridge.bootstrapCommand()
            installer.execute(
                manifest,
                "ulimit -c 0; ulimit -t 90; ulimit -f 2048; ulimit -n 64; ulimit -u 32; exec python3 -I -c '$bootstrap'",
                EXECUTION_TIMEOUT_MS,
                workspaceHostPath = workspaceRoot.absolutePath,
                shouldStop = stopped,
                extraEnvironment = bridge.environment()
            )
        } finally {
            bridge.close()
        }
        val quotaAfter = workspace.quotaStatus()
        if (quotaAfter == "unsafe") return "error: Python finished, but workspace integrity validation failed; inspect the workspace before using its files.\n${result.take(MAX_OUTPUT_CHARS)}"
        if (quotaAfter == "exceeded") return "error: Python exceeded the 20 MiB workspace quota. Further execution is blocked until the workspace is cleaned.\n\${result.take(MAX_OUTPUT_CHARS)}"
        return result.take(MAX_OUTPUT_CHARS)
    }

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
