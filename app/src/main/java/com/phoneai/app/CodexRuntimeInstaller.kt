package com.phoneai.app

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Installs the official OpenAI Codex app-server release for Alpine ARM64/musl.
 * The Android host downloads and verifies the pinned archive before PRoot extracts it.
 * This avoids depending on guest Alpine networking, which is unreliable on some mobile networks.
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
        private const val ARCHIVE_NAME = "orion-codex-app-server-$VERSION.tar.gz"
        private const val MAX_ARCHIVE_BYTES = 200L * 1024L * 1024L

        private val installCommand = """
            set -eu
            archive="/android-app/$ARCHIVE_NAME"
            work="/tmp/orion-codex-$VERSION"
            test -s "${'$'}archive" || { echo "Host-verified Codex archive is missing from /android-app"; exit 30; }
            mkdir -p "${'$'}work" /usr/local/bin
            tar -tzf "${'$'}archive" > "${'$'}work/archive-files.txt"
            tar -xzf "${'$'}archive" -C "${'$'}work"
            # Upstream archives may use plain names or target-triple-suffixed names.
            for binary in codex-app-server codex-code-mode-host codex-exec-server; do
              candidate=$(find "${'
            '$GUEST_BINARY' --help >/dev/null
            rm -rf "${'$'}work"
        """.trimIndent()
    }

    private fun downloadVerifiedArchive(
        destination: File,
        onOutput: (String) -> Unit,
        shouldStop: () -> Boolean
    ) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, destination.name + ".part")
        var lastFailure: Throwable? = null
        repeat(3) { attempt ->
            check(!shouldStop()) { "Codex installation was cancelled." }
            temporary.delete()
            val connection = URL(ARCHIVE_URL).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 30_000
                connection.readTimeout = 120_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "Orion-Android/1.0 (Codex runtime installer)")
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    throw IOException("Codex download failed (HTTP ${connection.responseCode}).")
                }
                val expected = connection.contentLengthLong
                if (expected > MAX_ARCHIVE_BYTES) {
                    throw IOException("Codex archive exceeds the 200 MB safety limit.")
                }
                var received = 0L
                var lastPercent = -1
                connection.inputStream.buffered().use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            check(!shouldStop()) { "Codex installation was cancelled." }
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            if (received > MAX_ARCHIVE_BYTES) {
                                throw IOException("Codex archive exceeded the 200 MB safety limit.")
                            }
                            output.write(buffer, 0, count)
                            if (expected > 0L) {
                                val percent = (received * 100L / expected).toInt().coerceIn(0, 100)
                                if (percent != lastPercent && (percent % 5 == 0 || percent == 100)) {
                                    onOutput("Downloading verified Codex runtime: $percent%")
                                    lastPercent = percent
                                }
                            }
                        }
                    }
                }
                if (expected > 0L && received != expected) {
                    throw IOException("Codex download was incomplete: received $received of $expected bytes.")
                }
                check(!shouldStop()) { "Codex installation was cancelled." }
                if (!AssetIntegrity.matchesSha256(temporary, SHA256)) {
                    throw IOException("Codex archive SHA-256 verification failed; refusing to install it.")
                }
                if (destination.exists() && !destination.delete()) {
                    throw IOException("Could not replace the previous Codex archive.")
                }
                if (!temporary.renameTo(destination)) {
                    temporary.copyTo(destination, overwrite = true)
                    temporary.delete()
                }
                onOutput("Codex archive downloaded and SHA-256 verified (${received / (1024 * 1024)} MiB).")
                return
            } catch (failure: Exception) {
                lastFailure = failure
                temporary.delete()
                if (shouldStop()) throw InterruptedException("Codex installation was cancelled.")
                onOutput("Codex download attempt ${attempt + 1}/3 failed: ${failure.message ?: failure.javaClass.simpleName}")
                if (attempt < 2) Thread.sleep(1_000L * (attempt + 1))
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Could not download a checksum-valid Codex runtime after three attempts.", lastFailure)
    }

    fun install(onOutput: (String) -> Unit = {}, shouldStop: () -> Boolean = { false }): Result<Unit> =
        runCatching {
            check(!shouldStop()) { "Codex installation was cancelled." }
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
                val archive = File(app.cacheDir, ARCHIVE_NAME)
                try {
                    downloadVerifiedArchive(archive, onOutput, shouldStop)
                    val result = runtime.execute(
                        LinuxRuntimePolicy.productionManifest,
                        installCommand,
                        timeoutMs = 900_000L,
                        onOutput = onOutput,
                        shouldStop = shouldStop,
                        androidAppDataPath = app.cacheDir.absolutePath
                    )
                    check(result.startsWith("exit=0")) { result.takeLast(4_000) }
                } finally {
                    archive.delete()
                }
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
}work" -type f -name "${'
            '$GUEST_BINARY' --help >/dev/null
            rm -rf "${'$'}work"
        """.trimIndent()
    }

    private fun downloadVerifiedArchive(
        destination: File,
        onOutput: (String) -> Unit,
        shouldStop: () -> Boolean
    ) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, destination.name + ".part")
        var lastFailure: Throwable? = null
        repeat(3) { attempt ->
            check(!shouldStop()) { "Codex installation was cancelled." }
            temporary.delete()
            val connection = URL(ARCHIVE_URL).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 30_000
                connection.readTimeout = 120_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "Orion-Android/1.0 (Codex runtime installer)")
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    throw IOException("Codex download failed (HTTP ${connection.responseCode}).")
                }
                val expected = connection.contentLengthLong
                if (expected > MAX_ARCHIVE_BYTES) {
                    throw IOException("Codex archive exceeds the 200 MB safety limit.")
                }
                var received = 0L
                var lastPercent = -1
                connection.inputStream.buffered().use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            check(!shouldStop()) { "Codex installation was cancelled." }
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            if (received > MAX_ARCHIVE_BYTES) {
                                throw IOException("Codex archive exceeded the 200 MB safety limit.")
                            }
                            output.write(buffer, 0, count)
                            if (expected > 0L) {
                                val percent = (received * 100L / expected).toInt().coerceIn(0, 100)
                                if (percent != lastPercent && (percent % 5 == 0 || percent == 100)) {
                                    onOutput("Downloading verified Codex runtime: $percent%")
                                    lastPercent = percent
                                }
                            }
                        }
                    }
                }
                if (expected > 0L && received != expected) {
                    throw IOException("Codex download was incomplete: received $received of $expected bytes.")
                }
                check(!shouldStop()) { "Codex installation was cancelled." }
                if (!AssetIntegrity.matchesSha256(temporary, SHA256)) {
                    throw IOException("Codex archive SHA-256 verification failed; refusing to install it.")
                }
                if (destination.exists() && !destination.delete()) {
                    throw IOException("Could not replace the previous Codex archive.")
                }
                if (!temporary.renameTo(destination)) {
                    temporary.copyTo(destination, overwrite = true)
                    temporary.delete()
                }
                onOutput("Codex archive downloaded and SHA-256 verified (${received / (1024 * 1024)} MiB).")
                return
            } catch (failure: Exception) {
                lastFailure = failure
                temporary.delete()
                if (shouldStop()) throw InterruptedException("Codex installation was cancelled.")
                onOutput("Codex download attempt ${attempt + 1}/3 failed: ${failure.message ?: failure.javaClass.simpleName}")
                if (attempt < 2) Thread.sleep(1_000L * (attempt + 1))
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Could not download a checksum-valid Codex runtime after three attempts.", lastFailure)
    }

    fun install(onOutput: (String) -> Unit = {}, shouldStop: () -> Boolean = { false }): Result<Unit> =
        runCatching {
            check(!shouldStop()) { "Codex installation was cancelled." }
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
                val archive = File(app.cacheDir, ARCHIVE_NAME)
                try {
                    downloadVerifiedArchive(archive, onOutput, shouldStop)
                    val result = runtime.execute(
                        LinuxRuntimePolicy.productionManifest,
                        installCommand,
                        timeoutMs = 900_000L,
                        onOutput = onOutput,
                        shouldStop = shouldStop,
                        androidAppDataPath = app.cacheDir.absolutePath
                    )
                    check(result.startsWith("exit=0")) { result.takeLast(4_000) }
                } finally {
                    archive.delete()
                }
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
}binary" -print -quit)
              if [ -z "${'
            '$GUEST_BINARY' --help >/dev/null
            rm -rf "${'$'}work"
        """.trimIndent()
    }

    private fun downloadVerifiedArchive(
        destination: File,
        onOutput: (String) -> Unit,
        shouldStop: () -> Boolean
    ) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, destination.name + ".part")
        var lastFailure: Throwable? = null
        repeat(3) { attempt ->
            check(!shouldStop()) { "Codex installation was cancelled." }
            temporary.delete()
            val connection = URL(ARCHIVE_URL).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 30_000
                connection.readTimeout = 120_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "Orion-Android/1.0 (Codex runtime installer)")
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    throw IOException("Codex download failed (HTTP ${connection.responseCode}).")
                }
                val expected = connection.contentLengthLong
                if (expected > MAX_ARCHIVE_BYTES) {
                    throw IOException("Codex archive exceeds the 200 MB safety limit.")
                }
                var received = 0L
                var lastPercent = -1
                connection.inputStream.buffered().use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            check(!shouldStop()) { "Codex installation was cancelled." }
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            if (received > MAX_ARCHIVE_BYTES) {
                                throw IOException("Codex archive exceeded the 200 MB safety limit.")
                            }
                            output.write(buffer, 0, count)
                            if (expected > 0L) {
                                val percent = (received * 100L / expected).toInt().coerceIn(0, 100)
                                if (percent != lastPercent && (percent % 5 == 0 || percent == 100)) {
                                    onOutput("Downloading verified Codex runtime: $percent%")
                                    lastPercent = percent
                                }
                            }
                        }
                    }
                }
                if (expected > 0L && received != expected) {
                    throw IOException("Codex download was incomplete: received $received of $expected bytes.")
                }
                check(!shouldStop()) { "Codex installation was cancelled." }
                if (!AssetIntegrity.matchesSha256(temporary, SHA256)) {
                    throw IOException("Codex archive SHA-256 verification failed; refusing to install it.")
                }
                if (destination.exists() && !destination.delete()) {
                    throw IOException("Could not replace the previous Codex archive.")
                }
                if (!temporary.renameTo(destination)) {
                    temporary.copyTo(destination, overwrite = true)
                    temporary.delete()
                }
                onOutput("Codex archive downloaded and SHA-256 verified (${received / (1024 * 1024)} MiB).")
                return
            } catch (failure: Exception) {
                lastFailure = failure
                temporary.delete()
                if (shouldStop()) throw InterruptedException("Codex installation was cancelled.")
                onOutput("Codex download attempt ${attempt + 1}/3 failed: ${failure.message ?: failure.javaClass.simpleName}")
                if (attempt < 2) Thread.sleep(1_000L * (attempt + 1))
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Could not download a checksum-valid Codex runtime after three attempts.", lastFailure)
    }

    fun install(onOutput: (String) -> Unit = {}, shouldStop: () -> Boolean = { false }): Result<Unit> =
        runCatching {
            check(!shouldStop()) { "Codex installation was cancelled." }
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
                val archive = File(app.cacheDir, ARCHIVE_NAME)
                try {
                    downloadVerifiedArchive(archive, onOutput, shouldStop)
                    val result = runtime.execute(
                        LinuxRuntimePolicy.productionManifest,
                        installCommand,
                        timeoutMs = 900_000L,
                        onOutput = onOutput,
                        shouldStop = shouldStop,
                        androidAppDataPath = app.cacheDir.absolutePath
                    )
                    check(result.startsWith("exit=0")) { result.takeLast(4_000) }
                } finally {
                    archive.delete()
                }
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
}candidate" ]; then
                candidate=$(find "${'
            '$GUEST_BINARY' --help >/dev/null
            rm -rf "${'$'}work"
        """.trimIndent()
    }

    private fun downloadVerifiedArchive(
        destination: File,
        onOutput: (String) -> Unit,
        shouldStop: () -> Boolean
    ) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, destination.name + ".part")
        var lastFailure: Throwable? = null
        repeat(3) { attempt ->
            check(!shouldStop()) { "Codex installation was cancelled." }
            temporary.delete()
            val connection = URL(ARCHIVE_URL).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 30_000
                connection.readTimeout = 120_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "Orion-Android/1.0 (Codex runtime installer)")
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    throw IOException("Codex download failed (HTTP ${connection.responseCode}).")
                }
                val expected = connection.contentLengthLong
                if (expected > MAX_ARCHIVE_BYTES) {
                    throw IOException("Codex archive exceeds the 200 MB safety limit.")
                }
                var received = 0L
                var lastPercent = -1
                connection.inputStream.buffered().use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            check(!shouldStop()) { "Codex installation was cancelled." }
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            if (received > MAX_ARCHIVE_BYTES) {
                                throw IOException("Codex archive exceeded the 200 MB safety limit.")
                            }
                            output.write(buffer, 0, count)
                            if (expected > 0L) {
                                val percent = (received * 100L / expected).toInt().coerceIn(0, 100)
                                if (percent != lastPercent && (percent % 5 == 0 || percent == 100)) {
                                    onOutput("Downloading verified Codex runtime: $percent%")
                                    lastPercent = percent
                                }
                            }
                        }
                    }
                }
                if (expected > 0L && received != expected) {
                    throw IOException("Codex download was incomplete: received $received of $expected bytes.")
                }
                check(!shouldStop()) { "Codex installation was cancelled." }
                if (!AssetIntegrity.matchesSha256(temporary, SHA256)) {
                    throw IOException("Codex archive SHA-256 verification failed; refusing to install it.")
                }
                if (destination.exists() && !destination.delete()) {
                    throw IOException("Could not replace the previous Codex archive.")
                }
                if (!temporary.renameTo(destination)) {
                    temporary.copyTo(destination, overwrite = true)
                    temporary.delete()
                }
                onOutput("Codex archive downloaded and SHA-256 verified (${received / (1024 * 1024)} MiB).")
                return
            } catch (failure: Exception) {
                lastFailure = failure
                temporary.delete()
                if (shouldStop()) throw InterruptedException("Codex installation was cancelled.")
                onOutput("Codex download attempt ${attempt + 1}/3 failed: ${failure.message ?: failure.javaClass.simpleName}")
                if (attempt < 2) Thread.sleep(1_000L * (attempt + 1))
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Could not download a checksum-valid Codex runtime after three attempts.", lastFailure)
    }

    fun install(onOutput: (String) -> Unit = {}, shouldStop: () -> Boolean = { false }): Result<Unit> =
        runCatching {
            check(!shouldStop()) { "Codex installation was cancelled." }
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
                val archive = File(app.cacheDir, ARCHIVE_NAME)
                try {
                    downloadVerifiedArchive(archive, onOutput, shouldStop)
                    val result = runtime.execute(
                        LinuxRuntimePolicy.productionManifest,
                        installCommand,
                        timeoutMs = 900_000L,
                        onOutput = onOutput,
                        shouldStop = shouldStop,
                        androidAppDataPath = app.cacheDir.absolutePath
                    )
                    check(result.startsWith("exit=0")) { result.takeLast(4_000) }
                } finally {
                    archive.delete()
                }
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
}work" -type f -name "${'
            '$GUEST_BINARY' --help >/dev/null
            rm -rf "${'$'}work"
        """.trimIndent()
    }

    private fun downloadVerifiedArchive(
        destination: File,
        onOutput: (String) -> Unit,
        shouldStop: () -> Boolean
    ) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, destination.name + ".part")
        var lastFailure: Throwable? = null
        repeat(3) { attempt ->
            check(!shouldStop()) { "Codex installation was cancelled." }
            temporary.delete()
            val connection = URL(ARCHIVE_URL).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 30_000
                connection.readTimeout = 120_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "Orion-Android/1.0 (Codex runtime installer)")
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    throw IOException("Codex download failed (HTTP ${connection.responseCode}).")
                }
                val expected = connection.contentLengthLong
                if (expected > MAX_ARCHIVE_BYTES) {
                    throw IOException("Codex archive exceeds the 200 MB safety limit.")
                }
                var received = 0L
                var lastPercent = -1
                connection.inputStream.buffered().use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            check(!shouldStop()) { "Codex installation was cancelled." }
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            if (received > MAX_ARCHIVE_BYTES) {
                                throw IOException("Codex archive exceeded the 200 MB safety limit.")
                            }
                            output.write(buffer, 0, count)
                            if (expected > 0L) {
                                val percent = (received * 100L / expected).toInt().coerceIn(0, 100)
                                if (percent != lastPercent && (percent % 5 == 0 || percent == 100)) {
                                    onOutput("Downloading verified Codex runtime: $percent%")
                                    lastPercent = percent
                                }
                            }
                        }
                    }
                }
                if (expected > 0L && received != expected) {
                    throw IOException("Codex download was incomplete: received $received of $expected bytes.")
                }
                check(!shouldStop()) { "Codex installation was cancelled." }
                if (!AssetIntegrity.matchesSha256(temporary, SHA256)) {
                    throw IOException("Codex archive SHA-256 verification failed; refusing to install it.")
                }
                if (destination.exists() && !destination.delete()) {
                    throw IOException("Could not replace the previous Codex archive.")
                }
                if (!temporary.renameTo(destination)) {
                    temporary.copyTo(destination, overwrite = true)
                    temporary.delete()
                }
                onOutput("Codex archive downloaded and SHA-256 verified (${received / (1024 * 1024)} MiB).")
                return
            } catch (failure: Exception) {
                lastFailure = failure
                temporary.delete()
                if (shouldStop()) throw InterruptedException("Codex installation was cancelled.")
                onOutput("Codex download attempt ${attempt + 1}/3 failed: ${failure.message ?: failure.javaClass.simpleName}")
                if (attempt < 2) Thread.sleep(1_000L * (attempt + 1))
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Could not download a checksum-valid Codex runtime after three attempts.", lastFailure)
    }

    fun install(onOutput: (String) -> Unit = {}, shouldStop: () -> Boolean = { false }): Result<Unit> =
        runCatching {
            check(!shouldStop()) { "Codex installation was cancelled." }
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
                val archive = File(app.cacheDir, ARCHIVE_NAME)
                try {
                    downloadVerifiedArchive(archive, onOutput, shouldStop)
                    val result = runtime.execute(
                        LinuxRuntimePolicy.productionManifest,
                        installCommand,
                        timeoutMs = 900_000L,
                        onOutput = onOutput,
                        shouldStop = shouldStop,
                        androidAppDataPath = app.cacheDir.absolutePath
                    )
                    check(result.startsWith("exit=0")) { result.takeLast(4_000) }
                } finally {
                    archive.delete()
                }
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
}binary-aarch64-unknown-linux-musl" -print -quit)
              fi
              if [ -z "${'
            '$GUEST_BINARY' --help >/dev/null
            rm -rf "${'$'}work"
        """.trimIndent()
    }

    private fun downloadVerifiedArchive(
        destination: File,
        onOutput: (String) -> Unit,
        shouldStop: () -> Boolean
    ) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, destination.name + ".part")
        var lastFailure: Throwable? = null
        repeat(3) { attempt ->
            check(!shouldStop()) { "Codex installation was cancelled." }
            temporary.delete()
            val connection = URL(ARCHIVE_URL).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 30_000
                connection.readTimeout = 120_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "Orion-Android/1.0 (Codex runtime installer)")
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    throw IOException("Codex download failed (HTTP ${connection.responseCode}).")
                }
                val expected = connection.contentLengthLong
                if (expected > MAX_ARCHIVE_BYTES) {
                    throw IOException("Codex archive exceeds the 200 MB safety limit.")
                }
                var received = 0L
                var lastPercent = -1
                connection.inputStream.buffered().use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            check(!shouldStop()) { "Codex installation was cancelled." }
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            if (received > MAX_ARCHIVE_BYTES) {
                                throw IOException("Codex archive exceeded the 200 MB safety limit.")
                            }
                            output.write(buffer, 0, count)
                            if (expected > 0L) {
                                val percent = (received * 100L / expected).toInt().coerceIn(0, 100)
                                if (percent != lastPercent && (percent % 5 == 0 || percent == 100)) {
                                    onOutput("Downloading verified Codex runtime: $percent%")
                                    lastPercent = percent
                                }
                            }
                        }
                    }
                }
                if (expected > 0L && received != expected) {
                    throw IOException("Codex download was incomplete: received $received of $expected bytes.")
                }
                check(!shouldStop()) { "Codex installation was cancelled." }
                if (!AssetIntegrity.matchesSha256(temporary, SHA256)) {
                    throw IOException("Codex archive SHA-256 verification failed; refusing to install it.")
                }
                if (destination.exists() && !destination.delete()) {
                    throw IOException("Could not replace the previous Codex archive.")
                }
                if (!temporary.renameTo(destination)) {
                    temporary.copyTo(destination, overwrite = true)
                    temporary.delete()
                }
                onOutput("Codex archive downloaded and SHA-256 verified (${received / (1024 * 1024)} MiB).")
                return
            } catch (failure: Exception) {
                lastFailure = failure
                temporary.delete()
                if (shouldStop()) throw InterruptedException("Codex installation was cancelled.")
                onOutput("Codex download attempt ${attempt + 1}/3 failed: ${failure.message ?: failure.javaClass.simpleName}")
                if (attempt < 2) Thread.sleep(1_000L * (attempt + 1))
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Could not download a checksum-valid Codex runtime after three attempts.", lastFailure)
    }

    fun install(onOutput: (String) -> Unit = {}, shouldStop: () -> Boolean = { false }): Result<Unit> =
        runCatching {
            check(!shouldStop()) { "Codex installation was cancelled." }
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
                val archive = File(app.cacheDir, ARCHIVE_NAME)
                try {
                    downloadVerifiedArchive(archive, onOutput, shouldStop)
                    val result = runtime.execute(
                        LinuxRuntimePolicy.productionManifest,
                        installCommand,
                        timeoutMs = 900_000L,
                        onOutput = onOutput,
                        shouldStop = shouldStop,
                        androidAppDataPath = app.cacheDir.absolutePath
                    )
                    check(result.startsWith("exit=0")) { result.takeLast(4_000) }
                } finally {
                    archive.delete()
                }
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
}candidate" ]; then
                echo "Codex archive missing required executable: ${'
            '$GUEST_BINARY' --help >/dev/null
            rm -rf "${'$'}work"
        """.trimIndent()
    }

    private fun downloadVerifiedArchive(
        destination: File,
        onOutput: (String) -> Unit,
        shouldStop: () -> Boolean
    ) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, destination.name + ".part")
        var lastFailure: Throwable? = null
        repeat(3) { attempt ->
            check(!shouldStop()) { "Codex installation was cancelled." }
            temporary.delete()
            val connection = URL(ARCHIVE_URL).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 30_000
                connection.readTimeout = 120_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "Orion-Android/1.0 (Codex runtime installer)")
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    throw IOException("Codex download failed (HTTP ${connection.responseCode}).")
                }
                val expected = connection.contentLengthLong
                if (expected > MAX_ARCHIVE_BYTES) {
                    throw IOException("Codex archive exceeds the 200 MB safety limit.")
                }
                var received = 0L
                var lastPercent = -1
                connection.inputStream.buffered().use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            check(!shouldStop()) { "Codex installation was cancelled." }
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            if (received > MAX_ARCHIVE_BYTES) {
                                throw IOException("Codex archive exceeded the 200 MB safety limit.")
                            }
                            output.write(buffer, 0, count)
                            if (expected > 0L) {
                                val percent = (received * 100L / expected).toInt().coerceIn(0, 100)
                                if (percent != lastPercent && (percent % 5 == 0 || percent == 100)) {
                                    onOutput("Downloading verified Codex runtime: $percent%")
                                    lastPercent = percent
                                }
                            }
                        }
                    }
                }
                if (expected > 0L && received != expected) {
                    throw IOException("Codex download was incomplete: received $received of $expected bytes.")
                }
                check(!shouldStop()) { "Codex installation was cancelled." }
                if (!AssetIntegrity.matchesSha256(temporary, SHA256)) {
                    throw IOException("Codex archive SHA-256 verification failed; refusing to install it.")
                }
                if (destination.exists() && !destination.delete()) {
                    throw IOException("Could not replace the previous Codex archive.")
                }
                if (!temporary.renameTo(destination)) {
                    temporary.copyTo(destination, overwrite = true)
                    temporary.delete()
                }
                onOutput("Codex archive downloaded and SHA-256 verified (${received / (1024 * 1024)} MiB).")
                return
            } catch (failure: Exception) {
                lastFailure = failure
                temporary.delete()
                if (shouldStop()) throw InterruptedException("Codex installation was cancelled.")
                onOutput("Codex download attempt ${attempt + 1}/3 failed: ${failure.message ?: failure.javaClass.simpleName}")
                if (attempt < 2) Thread.sleep(1_000L * (attempt + 1))
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Could not download a checksum-valid Codex runtime after three attempts.", lastFailure)
    }

    fun install(onOutput: (String) -> Unit = {}, shouldStop: () -> Boolean = { false }): Result<Unit> =
        runCatching {
            check(!shouldStop()) { "Codex installation was cancelled." }
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
                val archive = File(app.cacheDir, ARCHIVE_NAME)
                try {
                    downloadVerifiedArchive(archive, onOutput, shouldStop)
                    val result = runtime.execute(
                        LinuxRuntimePolicy.productionManifest,
                        installCommand,
                        timeoutMs = 900_000L,
                        onOutput = onOutput,
                        shouldStop = shouldStop,
                        androidAppDataPath = app.cacheDir.absolutePath
                    )
                    check(result.startsWith("exit=0")) { result.takeLast(4_000) }
                } finally {
                    archive.delete()
                }
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
}binary"
                cat "${'
            '$GUEST_BINARY' --help >/dev/null
            rm -rf "${'$'}work"
        """.trimIndent()
    }

    private fun downloadVerifiedArchive(
        destination: File,
        onOutput: (String) -> Unit,
        shouldStop: () -> Boolean
    ) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, destination.name + ".part")
        var lastFailure: Throwable? = null
        repeat(3) { attempt ->
            check(!shouldStop()) { "Codex installation was cancelled." }
            temporary.delete()
            val connection = URL(ARCHIVE_URL).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 30_000
                connection.readTimeout = 120_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "Orion-Android/1.0 (Codex runtime installer)")
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    throw IOException("Codex download failed (HTTP ${connection.responseCode}).")
                }
                val expected = connection.contentLengthLong
                if (expected > MAX_ARCHIVE_BYTES) {
                    throw IOException("Codex archive exceeds the 200 MB safety limit.")
                }
                var received = 0L
                var lastPercent = -1
                connection.inputStream.buffered().use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            check(!shouldStop()) { "Codex installation was cancelled." }
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            if (received > MAX_ARCHIVE_BYTES) {
                                throw IOException("Codex archive exceeded the 200 MB safety limit.")
                            }
                            output.write(buffer, 0, count)
                            if (expected > 0L) {
                                val percent = (received * 100L / expected).toInt().coerceIn(0, 100)
                                if (percent != lastPercent && (percent % 5 == 0 || percent == 100)) {
                                    onOutput("Downloading verified Codex runtime: $percent%")
                                    lastPercent = percent
                                }
                            }
                        }
                    }
                }
                if (expected > 0L && received != expected) {
                    throw IOException("Codex download was incomplete: received $received of $expected bytes.")
                }
                check(!shouldStop()) { "Codex installation was cancelled." }
                if (!AssetIntegrity.matchesSha256(temporary, SHA256)) {
                    throw IOException("Codex archive SHA-256 verification failed; refusing to install it.")
                }
                if (destination.exists() && !destination.delete()) {
                    throw IOException("Could not replace the previous Codex archive.")
                }
                if (!temporary.renameTo(destination)) {
                    temporary.copyTo(destination, overwrite = true)
                    temporary.delete()
                }
                onOutput("Codex archive downloaded and SHA-256 verified (${received / (1024 * 1024)} MiB).")
                return
            } catch (failure: Exception) {
                lastFailure = failure
                temporary.delete()
                if (shouldStop()) throw InterruptedException("Codex installation was cancelled.")
                onOutput("Codex download attempt ${attempt + 1}/3 failed: ${failure.message ?: failure.javaClass.simpleName}")
                if (attempt < 2) Thread.sleep(1_000L * (attempt + 1))
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Could not download a checksum-valid Codex runtime after three attempts.", lastFailure)
    }

    fun install(onOutput: (String) -> Unit = {}, shouldStop: () -> Boolean = { false }): Result<Unit> =
        runCatching {
            check(!shouldStop()) { "Codex installation was cancelled." }
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
                val archive = File(app.cacheDir, ARCHIVE_NAME)
                try {
                    downloadVerifiedArchive(archive, onOutput, shouldStop)
                    val result = runtime.execute(
                        LinuxRuntimePolicy.productionManifest,
                        installCommand,
                        timeoutMs = 900_000L,
                        onOutput = onOutput,
                        shouldStop = shouldStop,
                        androidAppDataPath = app.cacheDir.absolutePath
                    )
                    check(result.startsWith("exit=0")) { result.takeLast(4_000) }
                } finally {
                    archive.delete()
                }
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
}work/archive-files.txt" | head -100
                find "${'
            '$GUEST_BINARY' --help >/dev/null
            rm -rf "${'$'}work"
        """.trimIndent()
    }

    private fun downloadVerifiedArchive(
        destination: File,
        onOutput: (String) -> Unit,
        shouldStop: () -> Boolean
    ) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, destination.name + ".part")
        var lastFailure: Throwable? = null
        repeat(3) { attempt ->
            check(!shouldStop()) { "Codex installation was cancelled." }
            temporary.delete()
            val connection = URL(ARCHIVE_URL).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 30_000
                connection.readTimeout = 120_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "Orion-Android/1.0 (Codex runtime installer)")
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    throw IOException("Codex download failed (HTTP ${connection.responseCode}).")
                }
                val expected = connection.contentLengthLong
                if (expected > MAX_ARCHIVE_BYTES) {
                    throw IOException("Codex archive exceeds the 200 MB safety limit.")
                }
                var received = 0L
                var lastPercent = -1
                connection.inputStream.buffered().use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            check(!shouldStop()) { "Codex installation was cancelled." }
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            if (received > MAX_ARCHIVE_BYTES) {
                                throw IOException("Codex archive exceeded the 200 MB safety limit.")
                            }
                            output.write(buffer, 0, count)
                            if (expected > 0L) {
                                val percent = (received * 100L / expected).toInt().coerceIn(0, 100)
                                if (percent != lastPercent && (percent % 5 == 0 || percent == 100)) {
                                    onOutput("Downloading verified Codex runtime: $percent%")
                                    lastPercent = percent
                                }
                            }
                        }
                    }
                }
                if (expected > 0L && received != expected) {
                    throw IOException("Codex download was incomplete: received $received of $expected bytes.")
                }
                check(!shouldStop()) { "Codex installation was cancelled." }
                if (!AssetIntegrity.matchesSha256(temporary, SHA256)) {
                    throw IOException("Codex archive SHA-256 verification failed; refusing to install it.")
                }
                if (destination.exists() && !destination.delete()) {
                    throw IOException("Could not replace the previous Codex archive.")
                }
                if (!temporary.renameTo(destination)) {
                    temporary.copyTo(destination, overwrite = true)
                    temporary.delete()
                }
                onOutput("Codex archive downloaded and SHA-256 verified (${received / (1024 * 1024)} MiB).")
                return
            } catch (failure: Exception) {
                lastFailure = failure
                temporary.delete()
                if (shouldStop()) throw InterruptedException("Codex installation was cancelled.")
                onOutput("Codex download attempt ${attempt + 1}/3 failed: ${failure.message ?: failure.javaClass.simpleName}")
                if (attempt < 2) Thread.sleep(1_000L * (attempt + 1))
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Could not download a checksum-valid Codex runtime after three attempts.", lastFailure)
    }

    fun install(onOutput: (String) -> Unit = {}, shouldStop: () -> Boolean = { false }): Result<Unit> =
        runCatching {
            check(!shouldStop()) { "Codex installation was cancelled." }
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
                val archive = File(app.cacheDir, ARCHIVE_NAME)
                try {
                    downloadVerifiedArchive(archive, onOutput, shouldStop)
                    val result = runtime.execute(
                        LinuxRuntimePolicy.productionManifest,
                        installCommand,
                        timeoutMs = 900_000L,
                        onOutput = onOutput,
                        shouldStop = shouldStop,
                        androidAppDataPath = app.cacheDir.absolutePath
                    )
                    check(result.startsWith("exit=0")) { result.takeLast(4_000) }
                } finally {
                    archive.delete()
                }
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
}work" -maxdepth 5 -print
                case "${'
            '$GUEST_BINARY' --help >/dev/null
            rm -rf "${'$'}work"
        """.trimIndent()
    }

    private fun downloadVerifiedArchive(
        destination: File,
        onOutput: (String) -> Unit,
        shouldStop: () -> Boolean
    ) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, destination.name + ".part")
        var lastFailure: Throwable? = null
        repeat(3) { attempt ->
            check(!shouldStop()) { "Codex installation was cancelled." }
            temporary.delete()
            val connection = URL(ARCHIVE_URL).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 30_000
                connection.readTimeout = 120_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "Orion-Android/1.0 (Codex runtime installer)")
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    throw IOException("Codex download failed (HTTP ${connection.responseCode}).")
                }
                val expected = connection.contentLengthLong
                if (expected > MAX_ARCHIVE_BYTES) {
                    throw IOException("Codex archive exceeds the 200 MB safety limit.")
                }
                var received = 0L
                var lastPercent = -1
                connection.inputStream.buffered().use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            check(!shouldStop()) { "Codex installation was cancelled." }
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            if (received > MAX_ARCHIVE_BYTES) {
                                throw IOException("Codex archive exceeded the 200 MB safety limit.")
                            }
                            output.write(buffer, 0, count)
                            if (expected > 0L) {
                                val percent = (received * 100L / expected).toInt().coerceIn(0, 100)
                                if (percent != lastPercent && (percent % 5 == 0 || percent == 100)) {
                                    onOutput("Downloading verified Codex runtime: $percent%")
                                    lastPercent = percent
                                }
                            }
                        }
                    }
                }
                if (expected > 0L && received != expected) {
                    throw IOException("Codex download was incomplete: received $received of $expected bytes.")
                }
                check(!shouldStop()) { "Codex installation was cancelled." }
                if (!AssetIntegrity.matchesSha256(temporary, SHA256)) {
                    throw IOException("Codex archive SHA-256 verification failed; refusing to install it.")
                }
                if (destination.exists() && !destination.delete()) {
                    throw IOException("Could not replace the previous Codex archive.")
                }
                if (!temporary.renameTo(destination)) {
                    temporary.copyTo(destination, overwrite = true)
                    temporary.delete()
                }
                onOutput("Codex archive downloaded and SHA-256 verified (${received / (1024 * 1024)} MiB).")
                return
            } catch (failure: Exception) {
                lastFailure = failure
                temporary.delete()
                if (shouldStop()) throw InterruptedException("Codex installation was cancelled.")
                onOutput("Codex download attempt ${attempt + 1}/3 failed: ${failure.message ?: failure.javaClass.simpleName}")
                if (attempt < 2) Thread.sleep(1_000L * (attempt + 1))
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Could not download a checksum-valid Codex runtime after three attempts.", lastFailure)
    }

    fun install(onOutput: (String) -> Unit = {}, shouldStop: () -> Boolean = { false }): Result<Unit> =
        runCatching {
            check(!shouldStop()) { "Codex installation was cancelled." }
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
                val archive = File(app.cacheDir, ARCHIVE_NAME)
                try {
                    downloadVerifiedArchive(archive, onOutput, shouldStop)
                    val result = runtime.execute(
                        LinuxRuntimePolicy.productionManifest,
                        installCommand,
                        timeoutMs = 900_000L,
                        onOutput = onOutput,
                        shouldStop = shouldStop,
                        androidAppDataPath = app.cacheDir.absolutePath
                    )
                    check(result.startsWith("exit=0")) { result.takeLast(4_000) }
                } finally {
                    archive.delete()
                }
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
}binary" in
                  codex-app-server) exit 31 ;;
                  codex-code-mode-host) exit 32 ;;
                  codex-exec-server) exit 33 ;;
                esac
              fi
              install -m 755 "${'
            '$GUEST_BINARY' --help >/dev/null
            rm -rf "${'$'}work"
        """.trimIndent()
    }

    private fun downloadVerifiedArchive(
        destination: File,
        onOutput: (String) -> Unit,
        shouldStop: () -> Boolean
    ) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, destination.name + ".part")
        var lastFailure: Throwable? = null
        repeat(3) { attempt ->
            check(!shouldStop()) { "Codex installation was cancelled." }
            temporary.delete()
            val connection = URL(ARCHIVE_URL).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 30_000
                connection.readTimeout = 120_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "Orion-Android/1.0 (Codex runtime installer)")
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    throw IOException("Codex download failed (HTTP ${connection.responseCode}).")
                }
                val expected = connection.contentLengthLong
                if (expected > MAX_ARCHIVE_BYTES) {
                    throw IOException("Codex archive exceeds the 200 MB safety limit.")
                }
                var received = 0L
                var lastPercent = -1
                connection.inputStream.buffered().use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            check(!shouldStop()) { "Codex installation was cancelled." }
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            if (received > MAX_ARCHIVE_BYTES) {
                                throw IOException("Codex archive exceeded the 200 MB safety limit.")
                            }
                            output.write(buffer, 0, count)
                            if (expected > 0L) {
                                val percent = (received * 100L / expected).toInt().coerceIn(0, 100)
                                if (percent != lastPercent && (percent % 5 == 0 || percent == 100)) {
                                    onOutput("Downloading verified Codex runtime: $percent%")
                                    lastPercent = percent
                                }
                            }
                        }
                    }
                }
                if (expected > 0L && received != expected) {
                    throw IOException("Codex download was incomplete: received $received of $expected bytes.")
                }
                check(!shouldStop()) { "Codex installation was cancelled." }
                if (!AssetIntegrity.matchesSha256(temporary, SHA256)) {
                    throw IOException("Codex archive SHA-256 verification failed; refusing to install it.")
                }
                if (destination.exists() && !destination.delete()) {
                    throw IOException("Could not replace the previous Codex archive.")
                }
                if (!temporary.renameTo(destination)) {
                    temporary.copyTo(destination, overwrite = true)
                    temporary.delete()
                }
                onOutput("Codex archive downloaded and SHA-256 verified (${received / (1024 * 1024)} MiB).")
                return
            } catch (failure: Exception) {
                lastFailure = failure
                temporary.delete()
                if (shouldStop()) throw InterruptedException("Codex installation was cancelled.")
                onOutput("Codex download attempt ${attempt + 1}/3 failed: ${failure.message ?: failure.javaClass.simpleName}")
                if (attempt < 2) Thread.sleep(1_000L * (attempt + 1))
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Could not download a checksum-valid Codex runtime after three attempts.", lastFailure)
    }

    fun install(onOutput: (String) -> Unit = {}, shouldStop: () -> Boolean = { false }): Result<Unit> =
        runCatching {
            check(!shouldStop()) { "Codex installation was cancelled." }
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
                val archive = File(app.cacheDir, ARCHIVE_NAME)
                try {
                    downloadVerifiedArchive(archive, onOutput, shouldStop)
                    val result = runtime.execute(
                        LinuxRuntimePolicy.productionManifest,
                        installCommand,
                        timeoutMs = 900_000L,
                        onOutput = onOutput,
                        shouldStop = shouldStop,
                        androidAppDataPath = app.cacheDir.absolutePath
                    )
                    check(result.startsWith("exit=0")) { result.takeLast(4_000) }
                } finally {
                    archive.delete()
                }
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
}candidate" "/usr/local/bin/${'
            '$GUEST_BINARY' --help >/dev/null
            rm -rf "${'$'}work"
        """.trimIndent()
    }

    private fun downloadVerifiedArchive(
        destination: File,
        onOutput: (String) -> Unit,
        shouldStop: () -> Boolean
    ) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, destination.name + ".part")
        var lastFailure: Throwable? = null
        repeat(3) { attempt ->
            check(!shouldStop()) { "Codex installation was cancelled." }
            temporary.delete()
            val connection = URL(ARCHIVE_URL).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 30_000
                connection.readTimeout = 120_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "Orion-Android/1.0 (Codex runtime installer)")
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    throw IOException("Codex download failed (HTTP ${connection.responseCode}).")
                }
                val expected = connection.contentLengthLong
                if (expected > MAX_ARCHIVE_BYTES) {
                    throw IOException("Codex archive exceeds the 200 MB safety limit.")
                }
                var received = 0L
                var lastPercent = -1
                connection.inputStream.buffered().use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            check(!shouldStop()) { "Codex installation was cancelled." }
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            if (received > MAX_ARCHIVE_BYTES) {
                                throw IOException("Codex archive exceeded the 200 MB safety limit.")
                            }
                            output.write(buffer, 0, count)
                            if (expected > 0L) {
                                val percent = (received * 100L / expected).toInt().coerceIn(0, 100)
                                if (percent != lastPercent && (percent % 5 == 0 || percent == 100)) {
                                    onOutput("Downloading verified Codex runtime: $percent%")
                                    lastPercent = percent
                                }
                            }
                        }
                    }
                }
                if (expected > 0L && received != expected) {
                    throw IOException("Codex download was incomplete: received $received of $expected bytes.")
                }
                check(!shouldStop()) { "Codex installation was cancelled." }
                if (!AssetIntegrity.matchesSha256(temporary, SHA256)) {
                    throw IOException("Codex archive SHA-256 verification failed; refusing to install it.")
                }
                if (destination.exists() && !destination.delete()) {
                    throw IOException("Could not replace the previous Codex archive.")
                }
                if (!temporary.renameTo(destination)) {
                    temporary.copyTo(destination, overwrite = true)
                    temporary.delete()
                }
                onOutput("Codex archive downloaded and SHA-256 verified (${received / (1024 * 1024)} MiB).")
                return
            } catch (failure: Exception) {
                lastFailure = failure
                temporary.delete()
                if (shouldStop()) throw InterruptedException("Codex installation was cancelled.")
                onOutput("Codex download attempt ${attempt + 1}/3 failed: ${failure.message ?: failure.javaClass.simpleName}")
                if (attempt < 2) Thread.sleep(1_000L * (attempt + 1))
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Could not download a checksum-valid Codex runtime after three attempts.", lastFailure)
    }

    fun install(onOutput: (String) -> Unit = {}, shouldStop: () -> Boolean = { false }): Result<Unit> =
        runCatching {
            check(!shouldStop()) { "Codex installation was cancelled." }
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
                val archive = File(app.cacheDir, ARCHIVE_NAME)
                try {
                    downloadVerifiedArchive(archive, onOutput, shouldStop)
                    val result = runtime.execute(
                        LinuxRuntimePolicy.productionManifest,
                        installCommand,
                        timeoutMs = 900_000L,
                        onOutput = onOutput,
                        shouldStop = shouldStop,
                        androidAppDataPath = app.cacheDir.absolutePath
                    )
                    check(result.startsWith("exit=0")) { result.takeLast(4_000) }
                } finally {
                    archive.delete()
                }
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
}binary"
            done
            test -x '$GUEST_BINARY'
            test -x '$GUEST_CODE_MODE_HOST'
            test -x '$GUEST_EXEC_SERVER'
            '$GUEST_BINARY' --help >/dev/null
            rm -rf "${'$'}work"
        """.trimIndent()
    }

    private fun downloadVerifiedArchive(
        destination: File,
        onOutput: (String) -> Unit,
        shouldStop: () -> Boolean
    ) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, destination.name + ".part")
        var lastFailure: Throwable? = null
        repeat(3) { attempt ->
            check(!shouldStop()) { "Codex installation was cancelled." }
            temporary.delete()
            val connection = URL(ARCHIVE_URL).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 30_000
                connection.readTimeout = 120_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "Orion-Android/1.0 (Codex runtime installer)")
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    throw IOException("Codex download failed (HTTP ${connection.responseCode}).")
                }
                val expected = connection.contentLengthLong
                if (expected > MAX_ARCHIVE_BYTES) {
                    throw IOException("Codex archive exceeds the 200 MB safety limit.")
                }
                var received = 0L
                var lastPercent = -1
                connection.inputStream.buffered().use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            check(!shouldStop()) { "Codex installation was cancelled." }
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            if (received > MAX_ARCHIVE_BYTES) {
                                throw IOException("Codex archive exceeded the 200 MB safety limit.")
                            }
                            output.write(buffer, 0, count)
                            if (expected > 0L) {
                                val percent = (received * 100L / expected).toInt().coerceIn(0, 100)
                                if (percent != lastPercent && (percent % 5 == 0 || percent == 100)) {
                                    onOutput("Downloading verified Codex runtime: $percent%")
                                    lastPercent = percent
                                }
                            }
                        }
                    }
                }
                if (expected > 0L && received != expected) {
                    throw IOException("Codex download was incomplete: received $received of $expected bytes.")
                }
                check(!shouldStop()) { "Codex installation was cancelled." }
                if (!AssetIntegrity.matchesSha256(temporary, SHA256)) {
                    throw IOException("Codex archive SHA-256 verification failed; refusing to install it.")
                }
                if (destination.exists() && !destination.delete()) {
                    throw IOException("Could not replace the previous Codex archive.")
                }
                if (!temporary.renameTo(destination)) {
                    temporary.copyTo(destination, overwrite = true)
                    temporary.delete()
                }
                onOutput("Codex archive downloaded and SHA-256 verified (${received / (1024 * 1024)} MiB).")
                return
            } catch (failure: Exception) {
                lastFailure = failure
                temporary.delete()
                if (shouldStop()) throw InterruptedException("Codex installation was cancelled.")
                onOutput("Codex download attempt ${attempt + 1}/3 failed: ${failure.message ?: failure.javaClass.simpleName}")
                if (attempt < 2) Thread.sleep(1_000L * (attempt + 1))
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Could not download a checksum-valid Codex runtime after three attempts.", lastFailure)
    }

    fun install(onOutput: (String) -> Unit = {}, shouldStop: () -> Boolean = { false }): Result<Unit> =
        runCatching {
            check(!shouldStop()) { "Codex installation was cancelled." }
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
                val archive = File(app.cacheDir, ARCHIVE_NAME)
                try {
                    downloadVerifiedArchive(archive, onOutput, shouldStop)
                    val result = runtime.execute(
                        LinuxRuntimePolicy.productionManifest,
                        installCommand,
                        timeoutMs = 900_000L,
                        onOutput = onOutput,
                        shouldStop = shouldStop,
                        androidAppDataPath = app.cacheDir.absolutePath
                    )
                    check(result.startsWith("exit=0")) { result.takeLast(4_000) }
                } finally {
                    archive.delete()
                }
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
