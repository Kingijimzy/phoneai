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
        const val SHA256 = "d6c4b92229a19c1aad431d3cbded91ab93700d7534e20b593f4895d189690338"
        const val ARCHIVE_URL =
            "https://github.com/openai/codex/releases/download/rust-v0.167.1/codex-app-server-package-aarch64-unknown-linux-musl.tar.gz"
        const val GUEST_BINARY = "/usr/local/bin/codex-app-server"
        const val GUEST_CODE_MODE_HOST = "/usr/local/bin/codex-code-mode-host"
        const val GUEST_EXEC_SERVER = "/usr/local/bin/codex-exec-server"

        private val installCommand = """
            set -eu
            apk add --no-cache curl tar coreutils
            mkdir -p /tmp/orion-codex-$VERSION /usr/local/bin
            curl -fL --retry 3 --connect-timeout 20 '$ARCHIVE_URL' -o /tmp/orion-codex-$VERSION/app-server.tar.gz
            echo '$SHA256  /tmp/orion-codex-$VERSION/app-server.tar.gz' | sha256sum -c -
            tar -xzf /tmp/orion-codex-$VERSION/app-server.tar.gz -C /tmp/orion-codex-$VERSION
            find /tmp/orion-codex-$VERSION -type f -name '*-aarch64-unknown-linux-musl' -exec sh -c '
              for file do
                name=$(basename "${'$'}file")
                case "${'$'}name" in
                  *-aarch64-unknown-linux-musl) name=${'$'}{name%-aarch64-unknown-linux-musl} ;;
                esac
                install -m 755 "${'$'}file" "/usr/local/bin/${'$'}name"
              done
            ' sh {} +
            test -x '$GUEST_BINARY'
            test -x '$GUEST_CODE_MODE_HOST'
            test -x '$GUEST_EXEC_SERVER'
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
                "test -x '$GUEST_BINARY' && test -x '$GUEST_CODE_MODE_HOST' && test -x '$GUEST_EXEC_SERVER'",
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
        }

    fun openAppServer(workspaceHostPath: String): CodexAppServerClient {
        // Do not start stdio against a missing/stale guest binary.
        // Codex uses PRoot guest execution, so Android's unavailable bubblewrap namespace
        // support is not a runtime prerequisite.
        install().getOrThrow()
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
