package com.phoneai.app

import android.content.Context

/**
 * Installs the official OpenAI Codex app-server release for Alpine ARM64/musl.
 * The pinned release digest is verified inside the guest before extraction.
 */
internal class CodexRuntimeInstaller(context: Context) {
    private val app = context.applicationContext
    private val runtime = LinuxRuntimeInstaller(app)

    companion object {
        const val VERSION = "0.167.1"
        const val SHA256 = "9d6d88aa001979d6d530ae181c4b365565cda780455525ca5134bf0a8f03df28"
        const val ARCHIVE_URL =
            "https://github.com/openai/codex/releases/download/rust-v0.167.1/codex-app-server-aarch64-unknown-linux-musl.tar.gz"
        const val GUEST_BINARY = "/usr/local/bin/codex-app-server"

        private val installCommand = """
            set -eu
            apk add --no-cache curl tar coreutils bubblewrap-static
            mkdir -p /tmp/orion-codex-$VERSION /usr/local/bin
            curl -fL --retry 3 --connect-timeout 20 '$ARCHIVE_URL' -o /tmp/orion-codex-$VERSION/app-server.tar.gz
            echo '$SHA256  /tmp/orion-codex-$VERSION/app-server.tar.gz' | sha256sum -c -
            tar -xzf /tmp/orion-codex-$VERSION/app-server.tar.gz -C /tmp/orion-codex-$VERSION
            find /tmp/orion-codex-$VERSION -type f ! -name '*.tar.gz' -exec sh -c '
              for file do
                name=$(basename "${'$'}file")
                case "${'$'}name" in
                  *-aarch64-unknown-linux-musl) name=${'$'}{name%-aarch64-unknown-linux-musl} ;;
                esac
                install -m 755 "${'$'}file" "/usr/local/bin/${'$'}name"
              done
            ' sh {} +
            test -x '$GUEST_BINARY'
            if ! command -v bwrap >/dev/null 2>&1; then
              bwrap_static="${'$'}(command -v bwrap.static || true)"
              test -n "${'$'}bwrap_static" || { echo 'bubblewrap-static did not install bwrap.static on this Alpine architecture' >&2; exit 42; }
              ln -sf "${'$'}bwrap_static" /usr/local/bin/bwrap
            fi
            command -v bwrap >/dev/null
            bwrap --version
            '$GUEST_BINARY' --help >/dev/null
            rm -rf /tmp/orion-codex-$VERSION
        """.trimIndent()
    }

    fun install(onOutput: (String) -> Unit = {}, shouldStop: () -> Boolean = { false }): Result<Unit> =
        runCatching {
            if (!runtime.isReady(LinuxRuntimePolicy.productionManifest)) {
                val base = runtime.install(LinuxRuntimePolicy.productionManifest)
                check(base.isSuccess) { "Alpine runtime installation failed: ${base.exceptionOrNull()?.message}" }
            }
            val probe = runtime.execute(
                LinuxRuntimePolicy.productionManifest,
                "test -x '$GUEST_BINARY'",
                timeoutMs = 15_000L
            )
            if (!probe.startsWith("exit=0")) {
                val result = runtime.execute(
                LinuxRuntimePolicy.productionManifest,
                installCommand,
                timeoutMs = 900_000L,
                onOutput = onOutput,
                shouldStop = shouldStop
            )
                check(result.startsWith("exit=0")) { result.takeLast(4_000) }
            }
            // An old Alpine guest may contain Codex but lack the optional sandbox executable.
            val sandbox = runtime.execute(
                LinuxRuntimePolicy.productionManifest,
                CodexSandboxSetupPolicy.ensureCommand,
                timeoutMs = 120_000L,
                onOutput = onOutput,
                shouldStop = shouldStop
            )
            check(sandbox.startsWith("exit=0")) {
                "Codex sandbox dependency setup failed: " + sandbox.takeLast(2_000)
            }
        }

    fun openAppServer(workspaceHostPath: String): CodexAppServerClient {
        val vault = CodexSessionVault(app)
        vault.prepare()
        val config = runtime.interactiveLaunchConfig(
            workspaceHostPath = workspaceHostPath,
            androidAppDataPath = app.cacheDir.absolutePath
        )
        val session = LinuxCommandSession.start(
            config,
            listOf(GUEST_BINARY, "--listen", "stdio://"),
            extraEnvironment = mapOf("CODEX_HOME" to "/android-app/codex-session")
        )
        // Bound the first on-device account/session initialization window.
        return CodexAppServerClient(session, requestTimeoutMs = 90_000L, onClose = { vault.persist() })
    }
}
}(command -v bwrap.static || true)"
              test -n "${'
            fi
            command -v bwrap >/dev/null
            bwrap --version
            '$GUEST_BINARY' --help >/dev/null
            rm -rf /tmp/orion-codex-$VERSION
        """.trimIndent()
    }

    fun install(onOutput: (String) -> Unit = {}, shouldStop: () -> Boolean = { false }): Result<Unit> =
        runCatching {
            if (!runtime.isReady(LinuxRuntimePolicy.productionManifest)) {
                val base = runtime.install(LinuxRuntimePolicy.productionManifest)
                check(base.isSuccess) { "Alpine runtime installation failed: ${base.exceptionOrNull()?.message}" }
            }
            val probe = runtime.execute(
                LinuxRuntimePolicy.productionManifest,
                "test -x '$GUEST_BINARY'",
                timeoutMs = 15_000L
            )
            if (!probe.startsWith("exit=0")) {
                val result = runtime.execute(
                LinuxRuntimePolicy.productionManifest,
                installCommand,
                timeoutMs = 900_000L,
                onOutput = onOutput,
                shouldStop = shouldStop
            )
                check(result.startsWith("exit=0")) { result.takeLast(4_000) }
            }
            // An old Alpine guest may contain Codex but lack the optional sandbox executable.
            val sandbox = runtime.execute(
                LinuxRuntimePolicy.productionManifest,
                CodexSandboxSetupPolicy.ensureCommand,
                timeoutMs = 120_000L,
                onOutput = onOutput,
                shouldStop = shouldStop
            )
            check(sandbox.startsWith("exit=0")) {
                "Codex sandbox dependency setup failed: " + sandbox.takeLast(2_000)
            }
        }

    fun openAppServer(workspaceHostPath: String): CodexAppServerClient {
        val vault = CodexSessionVault(app)
        vault.prepare()
        val config = runtime.interactiveLaunchConfig(
            workspaceHostPath = workspaceHostPath,
            androidAppDataPath = app.cacheDir.absolutePath
        )
        val session = LinuxCommandSession.start(
            config,
            listOf(GUEST_BINARY, "--listen", "stdio://"),
            extraEnvironment = mapOf("CODEX_HOME" to "/android-app/codex-session")
        )
        // Bound the first on-device account/session initialization window.
        return CodexAppServerClient(session, requestTimeoutMs = 90_000L, onClose = { vault.persist() })
    }
}
}bwrap_static" || { echo 'bubblewrap-static did not install bwrap.static on this Alpine architecture' >&2; exit 42; }
              ln -sf "${'
            fi
            command -v bwrap >/dev/null
            bwrap --version
            '$GUEST_BINARY' --help >/dev/null
            rm -rf /tmp/orion-codex-$VERSION
        """.trimIndent()
    }

    fun install(onOutput: (String) -> Unit = {}, shouldStop: () -> Boolean = { false }): Result<Unit> =
        runCatching {
            if (!runtime.isReady(LinuxRuntimePolicy.productionManifest)) {
                val base = runtime.install(LinuxRuntimePolicy.productionManifest)
                check(base.isSuccess) { "Alpine runtime installation failed: ${base.exceptionOrNull()?.message}" }
            }
            val probe = runtime.execute(
                LinuxRuntimePolicy.productionManifest,
                "test -x '$GUEST_BINARY'",
                timeoutMs = 15_000L
            )
            if (!probe.startsWith("exit=0")) {
                val result = runtime.execute(
                LinuxRuntimePolicy.productionManifest,
                installCommand,
                timeoutMs = 900_000L,
                onOutput = onOutput,
                shouldStop = shouldStop
            )
                check(result.startsWith("exit=0")) { result.takeLast(4_000) }
            }
            // An old Alpine guest may contain Codex but lack the optional sandbox executable.
            val sandbox = runtime.execute(
                LinuxRuntimePolicy.productionManifest,
                CodexSandboxSetupPolicy.ensureCommand,
                timeoutMs = 120_000L,
                onOutput = onOutput,
                shouldStop = shouldStop
            )
            check(sandbox.startsWith("exit=0")) {
                "Codex sandbox dependency setup failed: " + sandbox.takeLast(2_000)
            }
        }

    fun openAppServer(workspaceHostPath: String): CodexAppServerClient {
        val vault = CodexSessionVault(app)
        vault.prepare()
        val config = runtime.interactiveLaunchConfig(
            workspaceHostPath = workspaceHostPath,
            androidAppDataPath = app.cacheDir.absolutePath
        )
        val session = LinuxCommandSession.start(
            config,
            listOf(GUEST_BINARY, "--listen", "stdio://"),
            extraEnvironment = mapOf("CODEX_HOME" to "/android-app/codex-session")
        )
        // Bound the first on-device account/session initialization window.
        return CodexAppServerClient(session, requestTimeoutMs = 90_000L, onClose = { vault.persist() })
    }
}
}bwrap_static" /usr/local/bin/bwrap
            fi
            command -v bwrap >/dev/null
            bwrap --version
            '$GUEST_BINARY' --help >/dev/null
            rm -rf /tmp/orion-codex-$VERSION
        """.trimIndent()
    }

    fun install(onOutput: (String) -> Unit = {}, shouldStop: () -> Boolean = { false }): Result<Unit> =
        runCatching {
            if (!runtime.isReady(LinuxRuntimePolicy.productionManifest)) {
                val base = runtime.install(LinuxRuntimePolicy.productionManifest)
                check(base.isSuccess) { "Alpine runtime installation failed: ${base.exceptionOrNull()?.message}" }
            }
            val probe = runtime.execute(
                LinuxRuntimePolicy.productionManifest,
                "test -x '$GUEST_BINARY'",
                timeoutMs = 15_000L
            )
            if (!probe.startsWith("exit=0")) {
                val result = runtime.execute(
                LinuxRuntimePolicy.productionManifest,
                installCommand,
                timeoutMs = 900_000L,
                onOutput = onOutput,
                shouldStop = shouldStop
            )
                check(result.startsWith("exit=0")) { result.takeLast(4_000) }
            }
            // An old Alpine guest may contain Codex but lack the optional sandbox executable.
            val sandbox = runtime.execute(
                LinuxRuntimePolicy.productionManifest,
                CodexSandboxSetupPolicy.ensureCommand,
                timeoutMs = 120_000L,
                onOutput = onOutput,
                shouldStop = shouldStop
            )
            check(sandbox.startsWith("exit=0")) {
                "Codex sandbox dependency setup failed: " + sandbox.takeLast(2_000)
            }
        }

    fun openAppServer(workspaceHostPath: String): CodexAppServerClient {
        val vault = CodexSessionVault(app)
        vault.prepare()
        val config = runtime.interactiveLaunchConfig(
            workspaceHostPath = workspaceHostPath,
            androidAppDataPath = app.cacheDir.absolutePath
        )
        val session = LinuxCommandSession.start(
            config,
            listOf(GUEST_BINARY, "--listen", "stdio://"),
            extraEnvironment = mapOf("CODEX_HOME" to "/android-app/codex-session")
        )
        // Bound the first on-device account/session initialization window.
        return CodexAppServerClient(session, requestTimeoutMs = 90_000L, onClose = { vault.persist() })
    }
}
