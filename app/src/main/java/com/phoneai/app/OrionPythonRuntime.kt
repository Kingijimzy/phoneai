package com.phoneai.app

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

/**
 * Full Python execution uses CPython installed in Orion's pinned Alpine/PRoot guest.
 * Generated extensions select either the restricted Chaquopy transform runtime or full CPython.
 *
 * PRoot runs under the app UID (it is not a kernel security boundary). The project workspace
 * is bind-mounted; Android app permissions and the OS sandbox govern any other host access.
 */
internal object OrionPythonRuntime {
    private const val SCRIPT_NAME = "orion_main.py"
    private const val MAX_OUTPUT_CHARS = 96_000
    private const val EXECUTION_TIMEOUT_MS = LinuxRuntimePolicy.MAX_PROCESS_TIMEOUT_MS
    private const val FULL_EXECUTION_TIMEOUT_MS = LinuxRuntimePolicy.MAX_PROCESS_TIMEOUT_MS

    @Synchronized
    fun execute(context: Context, code: String, confirm: (String) -> Boolean = { false }, stopped: () -> Boolean = { false }, onOutput: (String) -> Unit = {}, toolInvoker: ((String, org.json.JSONObject) -> String)? = null, forceFullProfile: Boolean = false): String {
        val maxChars = if (forceFullProfile) PythonExecutionPolicy.FULL_MAX_CODE_CHARS else PythonExecutionPolicy.maxCodeChars(context)
        if (code.isBlank()) return "error: Python code is required."
        if (code.length > maxChars) return "error: Python code exceeds the active profile limit of $maxChars characters."
        if (code.contains('\u0000')) return "error: Python code contains a forbidden NUL character."

        if (stopped()) return "error: Python execution cancelled before launch."
        val app = context.applicationContext
        val workspaceRoot = File(app.filesDir, "orion-python/workspace")
        if (!workspaceRoot.exists()) workspaceRoot.mkdirs()
        if (!workspaceRoot.isDirectory) return "error: Python workspace directory is unavailable."
        val scriptFile = File(workspaceRoot, SCRIPT_NAME)
        try {
            scriptFile.writeText(code, Charsets.UTF_8)
        } catch (failure: Exception) {
            return "error: could not stage Python source (${failure.javaClass.simpleName})."
        }

        val installer = LinuxRuntimeInstaller(app)
        val manifest = LinuxRuntimePolicy.productionManifest
        if (!installer.isReady(manifest)) {
            val installed = installer.install(manifest)
            if (installed.isFailure) return runBundled(app, workspaceRoot, code, "Linux runtime install failed: ${(installed.exceptionOrNull()?.message ?: "unknown error").take(120)}")
        }

        // Package installation is persistent in the app-private Alpine rootfs. The owner's
        // explicit python_exec request authorizes this pinned runtime/package bootstrap.
        val probe = installer.execute(
            manifest, "python3 --version", 8_000L,
            workspaceHostPath = workspaceRoot.absolutePath,
            shouldStop = stopped,
            androidAppDataPath = app.applicationInfo.dataDir,
            sharedStoragePath = File("/storage/emulated/0").takeIf { it.isDirectory }?.absolutePath
        )
        if (!probe.startsWith("exit=0") || !probe.contains("Python 3.")) {
            // The acceptance workload requires full CPython and its standard library only.
            // py3-pip and build-base are unnecessary, large dependency trees on a 4–8 GB phone
            // and were the source of avoidable package-install failures. apk add refreshes indexes
            // as needed; do not run a separate apk update that doubles downloads.
            val install = installer.execute(
                manifest, "apk add --no-cache python3 || { rc=\$?; echo \"CPYTHON_INSTALL_DIAGNOSTICS\"; echo \"--- filesystem ---\"; df -h / /var/cache/apk 2>&1; echo \"--- inodes ---\"; df -i / 2>&1; echo \"--- package state ---\"; apk info -e python3 2>&1; echo \"--- repositories ---\"; cat /etc/apk/repositories 2>&1; exit \$rc; }",
                LinuxRuntimePolicy.MAX_PROCESS_TIMEOUT_MS, onOutput,
                workspaceHostPath = workspaceRoot.absolutePath,
                shouldStop = stopped,
                androidAppDataPath = app.applicationInfo.dataDir,
                sharedStoragePath = File("/storage/emulated/0").takeIf { it.isDirectory }?.absolutePath
            )
            if (!install.startsWith("exit=0")) return runBundled(app, workspaceRoot, code, "CPython install in the Linux runtime failed: " + install.take(160).replace("\n", " "))
            val verified = installer.execute(
            manifest,
            "python3 --version && python3 -c 'import sys, platform; print(sys.executable); print(platform.machine()); print(sys.prefix)'",
            12_000L,
            workspaceHostPath = workspaceRoot.absolutePath,
            shouldStop = stopped,
            androidAppDataPath = app.applicationInfo.dataDir,
            sharedStoragePath = File("/storage/emulated/0").takeIf { it.isDirectory }?.absolutePath
        )
            if (!verified.startsWith("exit=0") || !verified.contains("Python 3.")) {
                return runBundled(app, workspaceRoot, code, "Linux CPython verification failed")
            }
        }

        val phoneApi = OrionPythonPhoneApi(app, readOnly = ToolPermissions.level(app) == "read_only", toolInvoker = toolInvoker)
        val bridge = try { OrionPythonPhoneBridge(phoneApi) }
        catch (failure: Exception) {
            return "error: could not start the private Python-to-Android bridge (${failure.javaClass.simpleName})."
        }
        val result = try {
            val full = forceFullProfile || OrionOwnerDialSettings.read(app).pythonProfile == OrionPythonProfile.FULL
            val bootstrap = OrionPythonPhoneBridge.bootstrapCommand(full)
            installer.execute(
                manifest,
                "cd /workspace && exec python3 -I -c '$bootstrap'",
                if (full) FULL_EXECUTION_TIMEOUT_MS else EXECUTION_TIMEOUT_MS,
                onOutput = onOutput,
                workspaceHostPath = workspaceRoot.absolutePath,
                androidAppDataPath = app.applicationInfo.dataDir,
                sharedStoragePath = File("/storage/emulated/0").takeIf { it.isDirectory }?.absolutePath,
                shouldStop = stopped,
                extraEnvironment = bridge.environment()
            )
        } finally {
            bridge.close()
        }
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

    /**
     * Run a generated python_full extension in the full CPython workspace runtime.
     * Source and input are base64-transported so user code cannot alter the wrapper quoting.
     * The extension receives INPUT and returns RESULT.
     */
    @Synchronized
    fun runFullExtension(context: Context, source: String, inputJson: String): String {
        val source64 = android.util.Base64.encodeToString(source.toByteArray(Charsets.UTF_8), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
        val input64 = android.util.Base64.encodeToString(inputJson.toByteArray(Charsets.UTF_8), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
        val wrapper = """
import base64, json, contextlib, io
INPUT = json.loads(base64.urlsafe_b64decode("$input64").decode("utf-8"))
__orion_source = base64.urlsafe_b64decode("$source64").decode("utf-8")
__orion_printed = io.StringIO()
try:
    with contextlib.redirect_stdout(__orion_printed):
        exec(compile(__orion_source, "<orion-full-extension>", "exec"), globals(), globals())
    __orion_reply = {"ok": True, "result": globals().get("RESULT"), "printed": __orion_printed.getvalue()}
except BaseException as __orion_error:
    __orion_reply = {"ok": False, "error": type(__orion_error).__name__ + ": " + str(__orion_error)[:500], "printed": __orion_printed.getvalue()}
print("__ORION_FULL_EXTENSION__" + json.dumps(__orion_reply, separators=(",", ":")))
""".trimIndent()
        val execution = execute(context, wrapper, forceFullProfile = true)
        val marker = "__ORION_FULL_EXTENSION__"
        val resultLine = execution.lineSequence().lastOrNull { it.startsWith(marker) }
        return if (resultLine != null) resultLine.removePrefix(marker)
        else org.json.JSONObject().put("ok", false)
            .put("error", "Full CPython extension did not return a result: " + execution.take(500)).toString()
    }

    /** Restricted data-transform runner retained for extensions that explicitly select runtime=python. */
    @Synchronized
    fun runExtension(context: Context, source: String, inputJson: String): String = try {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        Python.getInstance().getModule("orion_exec").callAttr("run_extension", source, inputJson).toString()
    } catch (e: Exception) {
        org.json.JSONObject().put("ok", false)
            .put("error", "bundled Python failed safely (${(e.message ?: e.javaClass.simpleName).take(180)})").toString()
    }
}
