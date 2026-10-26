package com.phoneai.app

/**
 * Maps only concrete, enabled connector entry points to integration capabilities.
 * Provider words and generic shell tools are not proof that a connector can execute.
 */
internal object OrionIntegrationCapabilityPolicy {
    fun capabilities(enabledTools: Set<String>, termuxReady: Boolean): Set<String> {
        val caps = linkedSetOf<String>()
        if ("github_account_read" in enabledTools || "github_private_repo_read" in enabledTools) {
            caps += "github.account.read"
        }
        if ("gmail_messages_read" in enabledTools || "gmail_list_messages" in enabledTools) {
            caps += "gmail.messages.read"
        }
        if ("google_drive_files_read" in enabledTools || "google_drive_list_files" in enabledTools) {
            caps += "google_drive.files.read"
        }
        // Connection/configuration is assessed separately by the planner.
        if ("telegram_inbox" in enabledTools && "telegram_send" in enabledTools) {
            caps += "telegram.messages"
        }
        if (termuxReady && "shell" in enabledTools && "termux_command" in enabledTools) {
            caps += "shell.execute"
        }
        return caps
    }
}
