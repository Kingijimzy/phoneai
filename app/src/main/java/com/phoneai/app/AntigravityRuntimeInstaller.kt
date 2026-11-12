package com.phoneai.app

import android.content.Context

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
            bash /tmp/orion-agy-install.sh --skip-aliases --skip-path
            test -x /root/.local/bin/agy
            /root/.local/bin/agy --version
            rm -f /tmp/orion-agy-install.sh
        """
    }

    private val configureCommand = """
        set -eu
        if ! command -v jq >/dev/null 2>&1; then apk add --no-cache jq; fi
        settings=/root/.gemini/antigravity-cli/settings.json
        mkdir -p /root/.gemini/antigravity-cli
        if [ -f "$settings" ]; then
          jq '. + {"trustedWorkspaces":["/workspace"],"toolPermission":"proceed-in-sandbox","enableTerminalSandbox":true}
            | .permissions = ((.permissions // {}) + {"deny": (((.permissions.deny // []) + ["command(sudo)","command(rm -rf)","read_file(/root/.ssh)","write_file(/root/.ssh)"]) | unique)})' "$settings" > "$settings.orion-tmp"
          mv "$settings.orion-tmp" "$settings"
        else
          printf '%s\n' '{"trustedWorkspaces":["/workspace"],"toolPermission":"proceed-in-sandbox","enableTerminalSandbox":true,"permissions":{"deny":["command(sudo)","command(rm -rf)","read_file(/root/.ssh)","write_file(/root/.ssh)"]}}' > "$settings"
        fi
    """.trimIndent()

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
            if (!probe.startsWith("exit=0")) {
                val result = runtime.execute(
                    LinuxRuntimePolicy.productionManifest,
                    installCommand,
                    timeoutMs = 600_000L,
                    onOutput = onOutput,
                    shouldStop = shouldStop
                )
                check(result.startsWith("exit=0")) { result.takeLast(4_000) }
            }
            val configured = runtime.execute(
                LinuxRuntimePolicy.productionManifest,
                configureCommand,
                timeoutMs = 60_000L,
                onOutput = onOutput,
                shouldStop = shouldStop
            )
            check(configured.startsWith("exit=0")) { "Antigravity sandbox configuration failed: " + configured.takeLast(2_000) }
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

    fun runPrompt(
        workspaceHostPath: String,
        prompt: String,
        model: String? = null,
        onSession: ((LinuxCommandSession) -> Unit)? = null,
        onDelta: ((String) -> Unit)? = null
    ): String {
        val session = openPromptSession(workspaceHostPath, prompt, model)
        onSession?.invoke(session)
        val output = StringBuilder()
        val diagnostics = StringBuilder()
        var finalStatus = ""
        var finalResponse = ""
        val deadline = System.currentTimeMillis() + 300_000L
        try {
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
                if (event.error.isNotBlank()) error("Antigravity: ${event.error}")
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
                "Antigravity model request failed: ${diagnostics.toString().take(500)}"
            }
            if (output.isEmpty()) output.append(finalResponse)
            val response = output.toString().trim()
            check(response.isNotBlank()) { "Antigravity returned no model response." }
            check(finalStatus.isBlank() || finalStatus == "SUCCESS") {
                "Antigravity did not complete successfully: $finalStatus"
            }
            return response
        } finally {
            session.close()
        }
    }
}
}settings" ]; then
          jq '. + {"trustedWorkspaces":["/workspace"],"toolPermission":"proceed-in-sandbox","enableTerminalSandbox":true}
            | .permissions = ((.permissions // {}) + {"deny": (((.permissions.deny // []) + ["command(sudo)","command(rm -rf)","read_file(/root/.ssh)","write_file(/root/.ssh)"]) | unique)})' "${'" > "$settings.orion-tmp"
          mv "$settings.orion-tmp" "$settings"
        else
          printf '%s\n' '{"trustedWorkspaces":["/workspace"],"toolPermission":"proceed-in-sandbox","enableTerminalSandbox":true,"permissions":{"deny":["command(sudo)","command(rm -rf)","read_file(/root/.ssh)","write_file(/root/.ssh)"]}}' > "$settings"
        fi
    """.trimIndent()

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
            if (!probe.startsWith("exit=0")) {
                val result = runtime.execute(
                    LinuxRuntimePolicy.productionManifest,
                    installCommand,
                    timeoutMs = 600_000L,
                    onOutput = onOutput,
                    shouldStop = shouldStop
                )
                check(result.startsWith("exit=0")) { result.takeLast(4_000) }
            }
            val configured = runtime.execute(
                LinuxRuntimePolicy.productionManifest,
                configureCommand,
                timeoutMs = 60_000L,
                onOutput = onOutput,
                shouldStop = shouldStop
            )
            check(configured.startsWith("exit=0")) { "Antigravity sandbox configuration failed: " + configured.takeLast(2_000) }
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

    fun runPrompt(
        workspaceHostPath: String,
        prompt: String,
        model: String? = null,
        onSession: ((LinuxCommandSession) -> Unit)? = null,
        onDelta: ((String) -> Unit)? = null
    ): String {
        val session = openPromptSession(workspaceHostPath, prompt, model)
        onSession?.invoke(session)
        val output = StringBuilder()
        val diagnostics = StringBuilder()
        var finalStatus = ""
        var finalResponse = ""
        val deadline = System.currentTimeMillis() + 300_000L
        try {
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
                if (event.error.isNotBlank()) error("Antigravity: ${event.error}")
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
                "Antigravity model request failed: ${diagnostics.toString().take(500)}"
            }
            if (output.isEmpty()) output.append(finalResponse)
            val response = output.toString().trim()
            check(response.isNotBlank()) { "Antigravity returned no model response." }
            check(finalStatus.isBlank() || finalStatus == "SUCCESS") {
                "Antigravity did not complete successfully: $finalStatus"
            }
            return response
        } finally {
            session.close()
        }
    }
}
}settings" > "${'.orion-tmp"
          mv "$settings.orion-tmp" "$settings"
        else
          printf '%s\n' '{"trustedWorkspaces":["/workspace"],"toolPermission":"proceed-in-sandbox","enableTerminalSandbox":true,"permissions":{"deny":["command(sudo)","command(rm -rf)","read_file(/root/.ssh)","write_file(/root/.ssh)"]}}' > "$settings"
        fi
    """.trimIndent()

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
            if (!probe.startsWith("exit=0")) {
                val result = runtime.execute(
                    LinuxRuntimePolicy.productionManifest,
                    installCommand,
                    timeoutMs = 600_000L,
                    onOutput = onOutput,
                    shouldStop = shouldStop
                )
                check(result.startsWith("exit=0")) { result.takeLast(4_000) }
            }
            val configured = runtime.execute(
                LinuxRuntimePolicy.productionManifest,
                configureCommand,
                timeoutMs = 60_000L,
                onOutput = onOutput,
                shouldStop = shouldStop
            )
            check(configured.startsWith("exit=0")) { "Antigravity sandbox configuration failed: " + configured.takeLast(2_000) }
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

    fun runPrompt(
        workspaceHostPath: String,
        prompt: String,
        model: String? = null,
        onSession: ((LinuxCommandSession) -> Unit)? = null,
        onDelta: ((String) -> Unit)? = null
    ): String {
        val session = openPromptSession(workspaceHostPath, prompt, model)
        onSession?.invoke(session)
        val output = StringBuilder()
        val diagnostics = StringBuilder()
        var finalStatus = ""
        var finalResponse = ""
        val deadline = System.currentTimeMillis() + 300_000L
        try {
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
                if (event.error.isNotBlank()) error("Antigravity: ${event.error}")
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
                "Antigravity model request failed: ${diagnostics.toString().take(500)}"
            }
            if (output.isEmpty()) output.append(finalResponse)
            val response = output.toString().trim()
            check(response.isNotBlank()) { "Antigravity returned no model response." }
            check(finalStatus.isBlank() || finalStatus == "SUCCESS") {
                "Antigravity did not complete successfully: $finalStatus"
            }
            return response
        } finally {
            session.close()
        }
    }
}
}settings.orion-tmp"
          mv "${'.orion-tmp" "$settings"
        else
          printf '%s\n' '{"trustedWorkspaces":["/workspace"],"toolPermission":"proceed-in-sandbox","enableTerminalSandbox":true,"permissions":{"deny":["command(sudo)","command(rm -rf)","read_file(/root/.ssh)","write_file(/root/.ssh)"]}}' > "$settings"
        fi
    """.trimIndent()

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
            if (!probe.startsWith("exit=0")) {
                val result = runtime.execute(
                    LinuxRuntimePolicy.productionManifest,
                    installCommand,
                    timeoutMs = 600_000L,
                    onOutput = onOutput,
                    shouldStop = shouldStop
                )
                check(result.startsWith("exit=0")) { result.takeLast(4_000) }
            }
            val configured = runtime.execute(
                LinuxRuntimePolicy.productionManifest,
                configureCommand,
                timeoutMs = 60_000L,
                onOutput = onOutput,
                shouldStop = shouldStop
            )
            check(configured.startsWith("exit=0")) { "Antigravity sandbox configuration failed: " + configured.takeLast(2_000) }
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

    fun runPrompt(
        workspaceHostPath: String,
        prompt: String,
        model: String? = null,
        onSession: ((LinuxCommandSession) -> Unit)? = null,
        onDelta: ((String) -> Unit)? = null
    ): String {
        val session = openPromptSession(workspaceHostPath, prompt, model)
        onSession?.invoke(session)
        val output = StringBuilder()
        val diagnostics = StringBuilder()
        var finalStatus = ""
        var finalResponse = ""
        val deadline = System.currentTimeMillis() + 300_000L
        try {
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
                if (event.error.isNotBlank()) error("Antigravity: ${event.error}")
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
                "Antigravity model request failed: ${diagnostics.toString().take(500)}"
            }
            if (output.isEmpty()) output.append(finalResponse)
            val response = output.toString().trim()
            check(response.isNotBlank()) { "Antigravity returned no model response." }
            check(finalStatus.isBlank() || finalStatus == "SUCCESS") {
                "Antigravity did not complete successfully: $finalStatus"
            }
            return response
        } finally {
            session.close()
        }
    }
}
}settings.orion-tmp" "${'"
        else
          printf '%s\n' '{"trustedWorkspaces":["/workspace"],"toolPermission":"proceed-in-sandbox","enableTerminalSandbox":true,"permissions":{"deny":["command(sudo)","command(rm -rf)","read_file(/root/.ssh)","write_file(/root/.ssh)"]}}' > "$settings"
        fi
    """.trimIndent()

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
            if (!probe.startsWith("exit=0")) {
                val result = runtime.execute(
                    LinuxRuntimePolicy.productionManifest,
                    installCommand,
                    timeoutMs = 600_000L,
                    onOutput = onOutput,
                    shouldStop = shouldStop
                )
                check(result.startsWith("exit=0")) { result.takeLast(4_000) }
            }
            val configured = runtime.execute(
                LinuxRuntimePolicy.productionManifest,
                configureCommand,
                timeoutMs = 60_000L,
                onOutput = onOutput,
                shouldStop = shouldStop
            )
            check(configured.startsWith("exit=0")) { "Antigravity sandbox configuration failed: " + configured.takeLast(2_000) }
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

    fun runPrompt(
        workspaceHostPath: String,
        prompt: String,
        model: String? = null,
        onSession: ((LinuxCommandSession) -> Unit)? = null,
        onDelta: ((String) -> Unit)? = null
    ): String {
        val session = openPromptSession(workspaceHostPath, prompt, model)
        onSession?.invoke(session)
        val output = StringBuilder()
        val diagnostics = StringBuilder()
        var finalStatus = ""
        var finalResponse = ""
        val deadline = System.currentTimeMillis() + 300_000L
        try {
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
                if (event.error.isNotBlank()) error("Antigravity: ${event.error}")
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
                "Antigravity model request failed: ${diagnostics.toString().take(500)}"
            }
            if (output.isEmpty()) output.append(finalResponse)
            val response = output.toString().trim()
            check(response.isNotBlank()) { "Antigravity returned no model response." }
            check(finalStatus.isBlank() || finalStatus == "SUCCESS") {
                "Antigravity did not complete successfully: $finalStatus"
            }
            return response
        } finally {
            session.close()
        }
    }
}
}settings"
        else
          printf '%s\n' '{"trustedWorkspaces":["/workspace"],"toolPermission":"proceed-in-sandbox","enableTerminalSandbox":true,"permissions":{"deny":["command(sudo)","command(rm -rf)","read_file(/root/.ssh)","write_file(/root/.ssh)"]}}' > "${'"
        fi
    """.trimIndent()

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
            if (!probe.startsWith("exit=0")) {
                val result = runtime.execute(
                    LinuxRuntimePolicy.productionManifest,
                    installCommand,
                    timeoutMs = 600_000L,
                    onOutput = onOutput,
                    shouldStop = shouldStop
                )
                check(result.startsWith("exit=0")) { result.takeLast(4_000) }
            }
            val configured = runtime.execute(
                LinuxRuntimePolicy.productionManifest,
                configureCommand,
                timeoutMs = 60_000L,
                onOutput = onOutput,
                shouldStop = shouldStop
            )
            check(configured.startsWith("exit=0")) { "Antigravity sandbox configuration failed: " + configured.takeLast(2_000) }
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

    fun runPrompt(
        workspaceHostPath: String,
        prompt: String,
        model: String? = null,
        onSession: ((LinuxCommandSession) -> Unit)? = null,
        onDelta: ((String) -> Unit)? = null
    ): String {
        val session = openPromptSession(workspaceHostPath, prompt, model)
        onSession?.invoke(session)
        val output = StringBuilder()
        val diagnostics = StringBuilder()
        var finalStatus = ""
        var finalResponse = ""
        val deadline = System.currentTimeMillis() + 300_000L
        try {
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
                if (event.error.isNotBlank()) error("Antigravity: ${event.error}")
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
                "Antigravity model request failed: ${diagnostics.toString().take(500)}"
            }
            if (output.isEmpty()) output.append(finalResponse)
            val response = output.toString().trim()
            check(response.isNotBlank()) { "Antigravity returned no model response." }
            check(finalStatus.isBlank() || finalStatus == "SUCCESS") {
                "Antigravity did not complete successfully: $finalStatus"
            }
            return response
        } finally {
            session.close()
        }
    }
}
}settings"
        fi
    """.trimIndent()

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
            if (!probe.startsWith("exit=0")) {
                val result = runtime.execute(
                    LinuxRuntimePolicy.productionManifest,
                    installCommand,
                    timeoutMs = 600_000L,
                    onOutput = onOutput,
                    shouldStop = shouldStop
                )
                check(result.startsWith("exit=0")) { result.takeLast(4_000) }
            }
            val configured = runtime.execute(
                LinuxRuntimePolicy.productionManifest,
                configureCommand,
                timeoutMs = 60_000L,
                onOutput = onOutput,
                shouldStop = shouldStop
            )
            check(configured.startsWith("exit=0")) { "Antigravity sandbox configuration failed: " + configured.takeLast(2_000) }
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

    fun runPrompt(
        workspaceHostPath: String,
        prompt: String,
        model: String? = null,
        onSession: ((LinuxCommandSession) -> Unit)? = null,
        onDelta: ((String) -> Unit)? = null
    ): String {
        val session = openPromptSession(workspaceHostPath, prompt, model)
        onSession?.invoke(session)
        val output = StringBuilder()
        val diagnostics = StringBuilder()
        var finalStatus = ""
        var finalResponse = ""
        val deadline = System.currentTimeMillis() + 300_000L
        try {
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
                if (event.error.isNotBlank()) error("Antigravity: ${event.error}")
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
                "Antigravity model request failed: ${diagnostics.toString().take(500)}"
            }
            if (output.isEmpty()) output.append(finalResponse)
            val response = output.toString().trim()
            check(response.isNotBlank()) { "Antigravity returned no model response." }
            check(finalStatus.isBlank() || finalStatus == "SUCCESS") {
                "Antigravity did not complete successfully: $finalStatus"
            }
            return response
        } finally {
            session.close()
        }
    }
}
