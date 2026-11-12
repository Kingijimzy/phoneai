package com.phoneai.app

import android.content.Context
import org.json.JSONObject

/** Installs and invokes Google's official Antigravity CLI in the Alpine ARM64 guest. */
internal class AntigravityRuntimeInstaller(context: Context) {
    private val runtime = LinuxRuntimeInstaller(context.applicationContext)

    companion object {
        const val VERSION_MARKER = "/root/.local/bin/agy"
        private const val installCommand = """
            set -eu
            apk add --no-cache bash curl ca-certificates jq
            mkdir -p /root/.local/bin
            curl -fsSL https://antigravity.google/cli/install.sh -o /tmp/orion-agy-install.sh
            bash /tmp/orion-agy-install.sh
            test -x /root/.local/bin/agy
            /root/.local/bin/agy --version
            mkdir -p /root/.gemini/antigravity-cli
            if [ ! -f /root/.gemini/antigravity-cli/settings.json ]; then
              printf '{"trustedWorkspaces":["/workspace"]}\n' > /root/.gemini/antigravity-cli/settings.json
            fi
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

    /** Signs in with Google in the terminal, prints a one-time code, then exits. */
    fun openLoginSession(workspaceHostPath: String): LinuxCommandSession {
        val config = runtime.interactiveLaunchConfig(workspaceHostPath)
        return LinuxCommandSession.start(config, listOf(VERSION_MARKER, "acp", "--login"))
    }

    /** Starts one headless prompt; an existing Google-account CLI session is required. */
    fun openPromptSession(workspaceHostPath: String, prompt: String, model: String? = null): LinuxCommandSession {
        val invocation = AntigravityCliProtocol.headlessPrompt(prompt, model)
        val config = runtime.interactiveLaunchConfig(workspaceHostPath)
        return LinuxCommandSession.start(config, listOf(VERSION_MARKER, *invocation.argv.drop(1).toTypedArray()))
    }

    fun listModels(workspaceHostPath: String): List<String> {
        val config = runtime.interactiveLaunchConfig(workspaceHostPath)
        val invocation = AntigravityCliProtocol.listModelsInvocation()
        val session = LinuxCommandSession.start(config, listOf(VERSION_MARKER, *invocation.argv.drop(1).toTypedArray()))
        val output = StringBuilder()
        val deadline = System.currentTimeMillis() + 60_000L
        try {
            while ((session.isAlive() || session.hasPendingOutput()) && System.currentTimeMillis() < deadline) {
                val line = session.readLine(500L)
                if (line != null) output.append(line).append('\n')
                else session.readStderrLine(20L)
            }
            check(!session.isAlive()) { "Antigravity model discovery timed out." }
            check(session.exitCodeOrNull() == 0) { "Antigravity model discovery failed." }
            return AntigravityCliProtocol.parseModelList(output.toString())
        } finally {
            session.close()
        }
    }

    private fun configureOrionMcpBridge(serverName: String, serverUrl: String) {
        require(serverName.matches(Regex("orion_phoneai_[a-f0-9]{12}"))) {
            "Invalid temporary Orion MCP server name."
        }
        require(serverUrl.startsWith("http://127.0.0.1:")) {
            "Orion MCP bridge must remain loopback-only."
        }
        val command = """
            set -eu
            command -v jq >/dev/null 2>&1 || apk add --no-cache jq
            mkdir -p /root/.gemini/config
            config=/root/.gemini/config/mcp_config.json
            [ -f "${'$'}config" ] || printf '{"mcpServers":{}}' > "${'$'}config"
            jq '.mcpServers = ((.mcpServers // {}) | with_entries(select(.key | startswith("orion_phoneai_") | not)))' "${'$'}config" > "${'$'}config.tmp"
            mv "${'$'}config.tmp" "${'$'}config"
            jq --arg name '$serverName' --arg url '$serverUrl' '.mcpServers[${'$'}name] = {"serverUrl":${'$'}url,"timeoutSeconds":600}' "${'$'}config" > "${'$'}config.tmp"
            mv "${'$'}config.tmp" "${'$'}config"
            settings=/root/.gemini/antigravity-cli/settings.json
            settings_backup=/tmp/orion_antigravity_settings_backup.json
            settings_missing=/tmp/orion_antigravity_settings_was_missing
            if [ -f "${'$'}settings_backup" ]; then mv "${'$'}settings_backup" "${'$'}settings"; fi
            if [ -f "${'$'}settings_missing" ]; then rm -f "${'$'}settings" "${'$'}settings_missing"; fi
            if [ -f "${'$'}settings" ]; then
              jq '.permissions.allow = ((.permissions.allow // []) | map(select(startswith("mcp(orion_phoneai_") | not)))' "${'$'}settings" > "${'$'}settings.tmp"
              mv "${'$'}settings.tmp" "${'$'}settings"
              cp "${'$'}settings" "${'$'}settings_backup"
            else
              touch "${'$'}settings_missing"
              printf '{}' > "${'$'}settings"
            fi
            rule="mcp($serverName/*)"
            jq --arg rule "${'$'}rule" '.permissions = (.permissions // {}) | .permissions.allow = (((.permissions.allow // []) + [${'$'}rule]) | unique) | .permissions.deny = (((.permissions.deny // []) + ["command(*)","unsandboxed(*)","read_file(*)","write_file(*)","read_url(*)","execute_url(*)"]) | unique)' "${'$'}settings" > "${'$'}settings.tmp"
            mv "${'$'}settings.tmp" "${'$'}settings"
        """.trimIndent()
        val result = runtime.execute(
            LinuxRuntimePolicy.productionManifest,
            command,
            timeoutMs = 30_000L
        )
        check(result.startsWith("exit=0")) {
            "Could not configure Orion's restricted Antigravity MCP bridge: " + result.takeLast(1_000)
        }
    }

    private fun clearOrionMcpBridge(serverName: String) {
        val command = """
            set -eu
            config=/root/.gemini/config/mcp_config.json
            if [ -f "${'$'}config" ] && command -v jq >/dev/null 2>&1; then
              jq --arg name '$serverName' 'del(.mcpServers[${'$'}name])' "${'$'}config" > "${'$'}config.tmp" && mv "${'$'}config.tmp" "${'$'}config"
            fi
            settings=/root/.gemini/antigravity-cli/settings.json
            settings_backup=/tmp/orion_antigravity_settings_backup.json
            settings_missing=/tmp/orion_antigravity_settings_was_missing
            if [ -f "${'$'}settings_missing" ]; then
              rm -f "${'$'}settings" "${'$'}settings_backup" "${'$'}settings_missing"
            elif [ -f "${'$'}settings_backup" ]; then
              mv "${'$'}settings_backup" "${'$'}settings"
            fi
        """.trimIndent()
        runCatching {
            runtime.execute(LinuxRuntimePolicy.productionManifest, command, timeoutMs = 5_000L)
        }
    }

    fun runPrompt(
        workspaceHostPath: String,
        prompt: String,
        model: String? = null,
        tools: List<ToolDefinition> = emptyList(),
        onToolCall: ((String, JSONObject) -> String)? = null,
        onDelta: ((String) -> Unit)? = null,
        onSession: ((LinuxCommandSession) -> Unit)? = null
    ): String {
        val bridge = if (tools.isNotEmpty() && onToolCall != null) {
            AntigravityOrionMcpBridge(tools) { name, args -> onToolCall(name, args) }
        } else null
        try {
            bridge?.let { configureOrionMcpBridge(it.serverName, it.serverUrl) }
            val session = openPromptSession(workspaceHostPath, prompt, model)
            try {
                onSession?.invoke(session)
                val output = StringBuilder()
                val diagnostics = StringBuilder()
                var finalStatus = ""
                var finalResponse = ""
                val deadline = System.currentTimeMillis() + 300_000L
                while ((session.isAlive() || session.hasPendingOutput()) && System.currentTimeMillis() < deadline) {
                    val line = session.readLine(500L)
                    if (line == null) {
                        session.readStderrLine(20L)?.let { diagnostics.append(it).append('\n') }
                        continue
                    }
                    val event = AntigravityCliProtocol.parseStreamEvent(line)
                    if (event == null) {
                        diagnostics.append(line).append('\n')
                        continue
                    }
                    if (AntigravityCliProtocol.isAuthenticationRequired(event)) {
                        error("Antigravity requires Google-account sign-in.")
                    }
                    if (event.error.isNotBlank()) error("Antigravity: " + event.error)
                    if (event.type == "step_update" && event.text.isNotBlank()) {
                        output.append(event.text)
                        onDelta?.invoke(event.text)
                    }
                    if (event.type == "result") {
                        finalStatus = event.status
                        finalResponse = event.text
                        if (finalStatus == "ERROR") {
                            error(event.error.ifBlank { finalResponse.ifBlank { "Antigravity returned an error." } })
                        }
                    }
                }
                check(!session.isAlive()) { "Antigravity model request timed out." }
                check(session.exitCodeOrNull() == 0) {
                    "Antigravity model request failed: " + diagnostics.toString().take(500)
                }
                if (output.isEmpty()) {
                    output.append(finalResponse)
                    if (finalResponse.isNotBlank()) onDelta?.invoke(finalResponse)
                }
                val response = output.toString().trim()
                check(response.isNotBlank()) { "Antigravity returned no model response." }
                check(finalStatus.isBlank() || finalStatus == "SUCCESS") {
                    "Antigravity did not complete successfully: " + finalStatus
                }
                return response
            } finally {
                session.close()
            }
        } finally {
            bridge?.let { current ->
                current.close()
                clearOrionMcpBridge(current.serverName)
            }
        }
    }
}
