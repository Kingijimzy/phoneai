package com.phoneai.app

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Owns API Anything inside Orion's existing Alpine/PRoot runtime and connects it via MCP. */
internal object ApiAnythingManager {
    private const val VERSION = "0.4.0"
    private const val HOST = "127.0.0.1"
    private const val PORT = 8787
    private const val ENDPOINT = "http://$HOST:$PORT/mcp"
    private const val HEALTH = "http://$HOST:$PORT/v1/health"
    private const val PREFS = "phoneai_api_anything"
    private const val ENABLED = "managed_enabled"
    private const val WORKSPACE = "api-anything/workspace"

    @Volatile private var server: LinuxCommandSession? = null
    @Volatile private var lastState = "Not installed"
    @Volatile private var lastError = ""
    @Volatile private var healthVerified = false

    fun status(context: Context): String {
        val enabled = context.getSharedPreferences(PREFS, 0).getBoolean(ENABLED, false)
        val alive = server?.isAlive() == true
        val healthy = alive && healthVerified
        return when {
            healthy -> "API Anything is running locally. Its MCP tools are connected to Orion. Health is verified; website write operations are exposed and remain subject to Orion's MCP scopes."
            enabled && !alive -> "API Anything is enabled but its local server is not running. Orion will try to restart it when the app process starts; use Start / repair to start it now."
            lastError.isNotBlank() -> "API Anything: $lastState. Last error: $lastError"
            else -> "API Anything is not running. Install it into Orion's Alpine runtime to enable website discovery, learning, operation reuse and upstream self-repair."
        }
    }

    fun installAndStart(context: Context, onProgress: (String) -> Unit = {}): Result<String> = runCatching {
        synchronized(this) {
            lastError = ""
            lastState = "Preparing Orion Linux runtime"
            val app = context.applicationContext
            val runtime = LinuxRuntimeInstaller(app) { message, _, _ -> onProgress(message) }
            val manifest = LinuxRuntimePolicy.productionManifest
            if (!runtime.isReady(manifest)) {
                onProgress("Installing Orion Alpine runtime")
                runtime.install(manifest).getOrThrow()
            }
            val workspace = java.io.File(app.filesDir, WORKSPACE)
            check(workspace.mkdirs() || workspace.isDirectory) { "Could not create API Anything workspace." }
            check(workspace.isDirectory && workspace.canRead() && workspace.canWrite()) {
                "API Anything workspace is not writable."
            }

            if (!isInstalled(runtime, workspace.absolutePath)) {
                lastState = "Installing Node.js, Chromium and API Anything"
                onProgress("Installing Node.js, Chromium and API Anything $VERSION")
                val install = runtime.execute(
                    manifest, INSTALL_COMMAND, timeoutMs = 900_000L,
                    workspaceHostPath = workspace.absolutePath,
                    androidAppDataPath = app.applicationInfo.dataDir,
                    sharedStoragePath = java.io.File("/storage/emulated/0").takeIf { it.isDirectory }?.absolutePath
                )
                check(install.startsWith("exit=0")) {
                    "API Anything installation failed. ${install.takeLast(1_800)}"
                }
            }

            stopLocked()
            lastState = "Starting local API Anything MCP server"
            onProgress("Starting local API Anything MCP server")
            val session = LinuxCommandSession.start(
                runtime.interactiveLaunchConfig(
                    workspace.absolutePath, app.applicationInfo.dataDir,
                    java.io.File("/storage/emulated/0").takeIf { it.isDirectory }?.absolutePath
                ),
                listOf("/bin/sh", "-lc", "export API_ANYTHING_LOCAL_ONLY=1 API_ANYTHING_BROWSER=chromium; exec api-anything serve --host $HOST --port $PORT --allow-writes")
            )
            server = session
            val deadline = System.currentTimeMillis() + 60_000L
            var startup: JSONObject? = null
            while (System.currentTimeMillis() < deadline && session.isAlive()) {
                val line = session.readLine(500L) ?: continue
                val candidate = line.trim()
                if (candidate.startsWith("{") && candidate.contains("\"mcp\"")) {
                    startup = runCatching { JSONObject(candidate) }.getOrNull()
                    if (startup != null) break
                }
            }
            check(session.isAlive()) {
                "API Anything server exited during startup. ${session.stderrSnapshot().joinToString(" ").take(500)}"
            }
            check(startup != null && startup.optBoolean("ok")) {
                "API Anything did not report a successful startup. ${session.stderrSnapshot().joinToString(" ").take(500)}"
            }

            val tokenResult = runtime.execute(
                manifest, "cat /root/.api-anything/serve-token", timeoutMs = 8_000L,
                workspaceHostPath = workspace.absolutePath,
                androidAppDataPath = app.applicationInfo.dataDir,
                sharedStoragePath = java.io.File("/storage/emulated/0").takeIf { it.isDirectory }?.absolutePath
            )
            check(tokenResult.startsWith("exit=0")) { "Could not read API Anything's private local bearer token." }
            val token = tokenResult.substringAfter('\n').trim()
            check(token.matches(Regex("^[a-fA-F0-9]{64}$"))) {
                "API Anything returned an unexpected token-file value; refusing to configure MCP."
            }
            val existingEndpoint = McpConnector.endpoint(app)
            check(existingEndpoint.isBlank() || existingEndpoint == ENDPOINT) {
                "Another MCP endpoint is already configured ($existingEndpoint). Disconnect it in MCP settings before enabling the managed API Anything endpoint; Orion will not silently replace an existing integration."
            }
            val saved = McpConnector.saveManagedLocal(app, ENDPOINT, token)
            check(saved.startsWith("MCP endpoint saved")) { saved }
            check(healthCheck()) { "API Anything health endpoint did not become ready." }
            healthVerified = true
            val catalogue = McpConnector.listTools(app)
            check(catalogue.startsWith("MCP tools")) {
                "API Anything started, but MCP discovery failed: ${catalogue.take(500)}"
            }
            app.getSharedPreferences(PREFS, 0).edit().putBoolean(ENABLED, true).commit()
            lastState = "Running and connected"
            onProgress("API Anything is running; MCP tool catalogue verified")
            "API Anything $VERSION installed and running. Orion verified its local health endpoint and discovered its MCP tools. Website discovery, operation learning, reads and self-repair are now available through Orion's shared tool runtime."
        }
    }.onFailure { failure ->
        synchronized(this) {
            lastError = (failure.message ?: failure.javaClass.simpleName).replace('\n', ' ').take(700)
            lastState = "Setup failed"
            stopLocked()
        }
    }

    /** Restarts the managed server after Android recreates the application process. */
    fun resumeIfEnabled(context: Context) {
        val app = context.applicationContext
        if (!app.getSharedPreferences(PREFS, 0).getBoolean(ENABLED, false) || server?.isAlive() == true) return
        Thread({
            synchronized(this) {
                if (server?.isAlive() == true) return@synchronized
                val runtime = LinuxRuntimeInstaller(app)
                val workspace = java.io.File(app.filesDir, WORKSPACE)
                if (!runtime.isReady(LinuxRuntimePolicy.productionManifest) || !workspace.isDirectory) return@synchronized
                val probe = runtime.execute(
                    LinuxRuntimePolicy.productionManifest,
                    "command -v api-anything && node -e 'const [a,b]=process.versions.node.split(\".\").map(Number);if(a<22||(a===22&&b<13))process.exit(1)'",
                    timeoutMs = 10_000L, workspaceHostPath = workspace.absolutePath,
                    androidAppDataPath = app.applicationInfo.dataDir
                )
                if (!probe.startsWith("exit=0")) return@synchronized
                val session = LinuxCommandSession.start(
                    runtime.interactiveLaunchConfig(workspace.absolutePath, app.applicationInfo.dataDir),
                    listOf("/bin/sh", "-lc", "export API_ANYTHING_LOCAL_ONLY=1 API_ANYTHING_BROWSER=chromium; exec api-anything serve --host $HOST --port $PORT --allow-writes")
                )
                server = session
                val deadline = System.currentTimeMillis() + 30_000L
                while (System.currentTimeMillis() < deadline && session.isAlive()) {
                    val line = session.readLine(500L) ?: continue
                    if (line.trim().startsWith("{") && line.contains("\"mcp\"")) break
                }
                if (!session.isAlive() || !healthCheck()) {
                    stopLocked()
                    healthVerified = false
                    lastError = "Managed server did not recover after process recreation."
                    lastState = "Recovery failed"
                } else {
                    healthVerified = true
                    lastState = "Running and connected"
                    lastError = ""
                }
            }
        }, "OrionApiAnythingRecovery").apply { isDaemon = true; start() }
    }

    fun stop(context: Context) {
        synchronized(this) {
            stopLocked()
            context.applicationContext.getSharedPreferences(PREFS, 0).edit().putBoolean(ENABLED, false).commit()
            lastState = "Stopped by owner"
        }
        McpConnector.clearManagedLocal(context.applicationContext, ENDPOINT)
    }

    private fun isInstalled(runtime: LinuxRuntimeInstaller, workspace: String): Boolean {
        val probe = runtime.execute(
            LinuxRuntimePolicy.productionManifest,
            "command -v api-anything >/dev/null 2>&1 && node -e 'const [a,b]=process.versions.node.split(\".\").map(Number);if(a<22||(a===22&&b<13))process.exit(1)' && chromium --version >/dev/null 2>&1",
            timeoutMs = 12_000L, workspaceHostPath = workspace
        )
        return probe.startsWith("exit=0")
    }

    private fun healthCheck(): Boolean = runCatching {
        val connection = (URL(HEALTH).openConnection() as HttpURLConnection).apply {
            connectTimeout = 1_500
            readTimeout = 1_500
            requestMethod = "GET"
        }
        try {
            connection.responseCode in 200..299 &&
                JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optBoolean("ok")
        } finally { connection.disconnect() }
    }.getOrDefault(false)

    private fun stopLocked() {
        server?.close()
        server = null
        healthVerified = false
    }

    private val INSTALL_COMMAND = """
        set -eu
        apk add --no-cache nodejs npm chromium
        node -e 'const [a,b]=process.versions.node.split(".").map(Number);if(a<22||(a===22&&b<13)){console.error("API Anything requires Node.js >=22.13.0; found "+process.versions.node);process.exit(2)}'
        npm install --global api-anything@$VERSION
        export API_ANYTHING_LOCAL_ONLY=1 API_ANYTHING_BROWSER=chromium
        api-anything setup --local-only --no-share
        api-anything --version
        chromium --version
    """.trimIndent()
}
