package com.phoneai.app

import android.content.Context

/** Installs and invokes Google's official Antigravity CLI in the Alpine ARM64 guest. */
internal class AntigravityRuntimeInstaller(context: Context) {
    private val runtime = LinuxRuntimeInstaller(context)

    companion object {
        const val VERSION_MARKER = "/root/.local/bin/agy"
        private const val installCommand = """
            set -eu
            apk add --no-cache bash curl ca-certificates
            mkdir -p /root/.local/bin
            curl -fsSL https://antigravity.google/cli/install.sh -o /tmp/orion-agy-install.sh
            bash /tmp/orion-agy-install.sh --skip-aliases --skip-path
            test -x /root/.local/bin/agy
            /root/.local/bin/agy --version
            rm -f /tmp/orion-agy-install.sh
        """
    }

    fun install(onOutput: (String) -> Unit = {}, shouldStop: () -> Boolean = { false }): Result<Unit> =
        runCatching {
            if (!runtime.isReady(LinuxRuntimePolicy.productionManifest)) {
                runtime.install(LinuxRuntimePolicy.productionManifest).getOrThrow()
            }
            val probe = runtime.execute(
                LinuxRuntimePolicy.productionManifest,
                "test -x '$VERSION_MARKER'",
                timeoutMs = 15_000L
            )
            if (probe.startsWith("exit=0")) return@runCatching Unit
            val result = runtime.execute(
                LinuxRuntimePolicy.productionManifest,
                installCommand,
                timeoutMs = 600_000L,
                onOutput = onOutput,
                shouldStop = shouldStop
            )
            check(result.startsWith("exit=0")) { result.takeLast(4_000) }
        }

    /** Starts the documented terminal-login flow; caller displays the CLI's URL/code output. */
    fun openLoginSession(workspaceHostPath: String): LinuxCommandSession {
        val config = runtime.interactiveLaunchConfig(workspaceHostPath)
        return LinuxCommandSession.start(config, listOf(VERSION_MARKER, "acp", "--login"))
    }

    /** Starts a single headless request; requires a previously authenticated agy session. */
    fun openPromptSession(workspaceHostPath: String, prompt: String): LinuxCommandSession {
        val invocation = AntigravityCliProtocol.headlessPrompt(prompt)
        val config = runtime.interactiveLaunchConfig(workspaceHostPath)
        return LinuxCommandSession.start(config, listOf(VERSION_MARKER, *invocation.argv.drop(1).toTypedArray()))
    }
}
