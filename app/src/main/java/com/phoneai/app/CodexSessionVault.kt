package com.phoneai.app

import android.content.Context
import java.io.File

/**
 * Bridges Codex's required auth.json format to Orion's Android-Keystore encrypted vault.
 * CODEX_HOME lives in filesDir (not cacheDir) so Android cache eviction cannot discard
 * native Codex threads between app launches. The plaintext auth file exists only while
 * Codex is running and is encrypted again when the client closes.
 */
internal class CodexSessionVault(context: Context) {
    private val app = context.applicationContext
    private val home = File(app.filesDir, "codex-session")
    private val legacyHome = File(app.cacheDir, "codex-session")
    private val authFile = File(home, "auth.json")

    fun prepare(): File {
        migrateLegacyHome(legacyHome, home)
        check(home.mkdirs() || home.isDirectory) { "Could not create private Codex session directory" }
        if (authFile.isFile) {
            val recovered = runCatching { authFile.readText() }.getOrNull().orEmpty()
            if (recovered.isNotBlank()) {
                check(ApiKeyVault.write(app, ENCRYPTED_KEY, LEGACY_KEY, recovered)) {
                    "Could not protect recovered Codex account credentials"
                }
                check(authFile.delete() || !authFile.exists()) {
                    "Could not remove recovered plaintext Codex credentials"
                }
            }
        }
        val encrypted = ApiKeyVault.read(app, ENCRYPTED_KEY, LEGACY_KEY)
        if (encrypted.isNotBlank()) authFile.writeText(encrypted)
        File(home, "config.toml").writeText(configToml())
        return home
    }

    fun persist() {
        if (!authFile.isFile) return
        val raw = runCatching { authFile.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        check(ApiKeyVault.write(app, ENCRYPTED_KEY, LEGACY_KEY, raw)) {
            "Could not encrypt Codex account credentials with Android Keystore"
        }
        check(authFile.delete() || !authFile.exists()) {
            "Could not remove plaintext Codex credentials after encryption"
        }
    }

    companion object {
        private const val ENCRYPTED_KEY = "enc_codex_account_auth"
        private const val LEGACY_KEY = "codex_account_auth"

        /**
         * One-time migration from the former cacheDir location. Move the whole Codex home
         * (including native thread databases) before starting the app-server. If a durable
         * destination already exists, copy only missing files and retain destination data.
         */
        internal fun migrateLegacyHome(legacy: File, destination: File) {
            if (!legacy.exists()) return
            check(legacy.isDirectory) { "Legacy Codex home is not a directory" }
            if (!destination.exists()) {
                check(destination.parentFile?.mkdirs() != false) { "Could not create persistent Codex parent directory" }
                if (legacy.renameTo(destination)) return
            }
            check(destination.mkdirs() || destination.isDirectory) { "Could not create persistent Codex home" }
            val root = legacy.canonicalFile
            legacy.walkTopDown().forEach { source ->
                val relative = source.relativeTo(legacy).path
                if (relative.isEmpty()) return@forEach
                val target = File(destination, relative)
                if (source.isDirectory) {
                    check(target.mkdirs() || target.isDirectory) { "Could not migrate Codex directory: $relative" }
                } else if (source.isFile && !target.exists()) {
                    target.parentFile?.let { parent ->
                        check(parent.mkdirs() || parent.isDirectory) { "Could not create Codex migration directory" }
                    }
                    source.copyTo(target, overwrite = false)
                }
            }
            check(legacy.deleteRecursively() || !legacy.exists()) {
                "Could not remove migrated legacy Codex cache directory"
            }
        }

        internal fun configToml(): String =
            // Codex runs inside Orion's Alpine PRoot guest. Bubblewrap/user namespaces
            // are unavailable on many Android kernels; PRoot already confines execution
            // to the guest plus the explicitly mounted Orion workspace and app cache.
            "approval_policy = \"never\"\n" +
                "sandbox_mode = \"danger-full-access\"\n" +
                "cli_auth_credentials_store = \"file\"\n" +
                "\n[features]\n" +
                "shell_tool = true\n" +
                "view_image = false\n" +
                "web_search = false\n" +
                "\n[agents]\n" +
                "enabled = false\n"
    }
}
