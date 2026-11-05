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
    private const val FULL_EXECUTION_TIMEOUT_MS = 300_000L

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
        // First-run runtime setup is unattended because every downloaded rootfs byte is
        // pinned by the release manifest, SHA-256 verified, smoke-tested in staging, and
        // atomically activated with rollback. No arbitrary image or installer script is run.
        if (!installer.isReady(manifest)) {
            if (stopped()) return "error: Python runtime installation cancelled before download."
            val installed = installer.install(manifest)
            if (installed.isFailure) return "error: Alpine runtime installation failed: ${(installed.exceptionOrNull()?.message ?: "unknown error").take(220)}"
        }

        // Install only CPython from the pinned official, signed Alpine repositories. The
        // isolated repositories file prevents a modified guest repo list from influencing
        // Autopilot. pip, compiler toolchains, alternate repositories and system upgrades
        // are deliberately not part of this automatic bootstrap.
        val probe = installer.execute(manifest, "python3 --version", 8_000L, workspaceHostPath = workspaceRoot.absolutePath, shouldStop = stopped)
        if (!probe.startsWith("exit=0") || !probe.contains("Python 3.")) {
            // The acceptance workload requires full CPython and its standard library only.
            // py3-pip and build-base are unnecessary, large dependency trees on a phone.
            // apk verifies signed indexes/packages from the exact pinned official repositories.
            val installCommand = """apk --no-cache --repositories-file /dev/null --repository https://dl-cdn.alpinelinux.org/alpine/v3.22/main --repository https://dl-cdn.alpinelinux.org/alpine/v3.22/community add python3 || { rc=${'$'}?; echo "CPYTHON_INSTALL_DIAGNOSTICS"; echo "--- filesystem ---"; df -h / /var/cache/apk 2>&1; echo "--- inodes ---"; df -i / 2>&1; echo "--- package state ---"; apk info -e python3 2>&1; echo "--- repositories ---"; cat /etc/apk/repositories 2>&1; exit ${'$'}rc; }"""
            val install = installer.execute(
                manifest, installCommand,
                180_000L, onOutput = onOutput, workspaceHostPath = workspaceRoot.absolutePath, shouldStop = stopped
            )
            if (!install.startsWith("exit=0")) return "error: CPython package installation failed; no Python execution was started. Full installer diagnostics follow:\n${install.take(MAX_OUTPUT_CHARS)}"
            val verified = installer.execute(manifest, "python3 --version && python3 -c 'import sys, platform; print(sys.executable); print(platform.machine()); print(sys.prefix)'", 12_000L, workspaceHostPath = workspaceRoot.absolutePath, shouldStop = stopped)
            if (!verified.startsWith("exit=0") || !verified.contains("Python 3.")) {
                return "error: apk reported success but CPython verification failed; execution was not started.\n${verified.take(MAX_OUTPUT_CHARS)}"
            }
        }

        // CPython can write directly through the bind mount, bypassing OrionPythonWorkspace.write().
        // Refuse to start with an already exhausted/unsafe workspace and audit it again after exit.
        when (workspace.quotaStatus()) {
            "ok" -> Unit
            "exceeded" -> return "error: Python workspace is already over its 256 MiB quota; remove files before execution."
            else -> return "error: Python workspace failed its integrity check; refusing execution."
        }

        val phoneApi = OrionPythonPhoneApi(app, confirm, readOnly = ToolPermissions.level(app) == "read_only", toolInvoker = toolInvoker)
        val bridge = try { OrionPythonPhoneBridge(phoneApi) }
        catch (failure: Exception) {
            return "error: could not start the private Python-to-Android bridge (${failure.javaClass.simpleName})."
        }
        var quotaExceededDuringRun = false
        val result = try {
            val full = OrionOwnerDialSettings.read(app).pythonProfile == OrionPythonProfile.FULL
            val bootstrap = OrionPythonPhoneBridge.bootstrapCommand(full)
            val limits = if (full) "ulimit -c 0; ulimit -t 280; ulimit -f 65536; ulimit -n 256; ulimit -u 128" else "ulimit -c 0; ulimit -t 90; ulimit -f 4096; ulimit -n 64; ulimit -u 32"
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
        if (quotaExceededDuringRun || quotaAfter == "exceeded") return "error: Python exceeded the 256 MiB workspace quota and was stopped. Further execution is blocked until the workspace is cleaned.\n${result.take(MAX_OUTPUT_CHARS)}"
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
