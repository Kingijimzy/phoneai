package com.phoneai.app

import android.content.Context
import java.io.File

/**
 * Bridges Codex's required auth.json format to Orion's Android-Keystore encrypted vault.
 * The plaintext file exists only in the app-private cache directory while Codex is running.
 */
internal class CodexSessionVault(context: Context) {
    private val app = context.applicationContext
    private val home = File(app.cacheDir, "codex-session")
    private val authFile = File(home, "auth.json")

    fun prepare(): File {
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
        File(home, "config.toml").writeText(
            "approval_policy = \"never\"\n" +
                "sandbox_mode = \"workspace-write\"\n" +
                "cli_auth_credentials_store = \"file\"\n"
        )
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
    }
}
