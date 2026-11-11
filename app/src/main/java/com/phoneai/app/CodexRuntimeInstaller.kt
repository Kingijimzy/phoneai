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
            apk add --no-cache curl tar coreutils
            mkdir -p /tmp/orion-codex-$VERSION /usr/local/bin
            curl -fL --retry 3 --connect-timeout 20 '$ARCHIVE_URL' -o /tmp/orion-codex-$VERSION/app-server.tar.gz
            echo '$SHA256  /tmp/orion-codex-$VERSION/app-server.tar.gz' | sha256sum -c -
            tar -xzf /tmp/orion-codex-$VERSION/app-server.tar.gz -C /tmp/orion-codex-$VERSION
            find /tmp/orion-codex-$VERSION -type f ! -name '*.tar.gz' -exec sh -c '
              for file do
                name=$(basename "$file")
                case "$name" in
                  *-aarch64-unknown-linux-musl) name=${name%-aarch64-unknown-linux-musl} ;;
                esac
                install -m 755 "$file" "/usr/local/bin/$name"
              done
            ' sh {} +
            test -x '$GUEST_BINARY'
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
            if (probe.startsWith("exit=0")) return@runCatching Unit
            val result = runtime.execute(
                LinuxRuntimePolicy.productionManifest,
                installCommand,
                timeoutMs = 900_000L,
                onOutput = onOutput,
                shouldStop = shouldStop
            )
            check(result.startsWith("exit=0")) { result.takeLast(4_000) }
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
            listOf(GUEST_BINARY),
            extraEnvironment = mapOf("CODEX_HOME" to "/android-app/codex-session")
        )
        return CodexAppServerClient(session, onClose = { vault.persist() })
    }
}
