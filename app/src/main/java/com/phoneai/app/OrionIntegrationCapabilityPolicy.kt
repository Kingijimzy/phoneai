package com.phoneai.app

/**
 * Maps only concrete, implemented connector entry points to integration capabilities.
 * Generic words in tool names/descriptions do not prove private account access exists.
 */
internal object OrionIntegrationCapabilityPolicy {
    fun capabilities(tools: Set<String>, telegramConfigured: Boolean): Set<String> {
        val caps = linkedSetOf<String>()
        if ("github_account_read" in tools || "github_private_repo_read" in tools) {
            caps += "github.account.read"
        }
        if ("gmail_messages_read" in tools || "gmail_list_messages" in tools) {
            caps += "gmail.messages.read"
        }
        if ("google_drive_files_read" in tools || "google_drive_list_files" in tools) {
            caps += "google_drive.files.read"
        }
        if (telegramConfigured && "telegram_inbox" in tools && "telegram_send" in tools) {
            caps += "telegram.messages"
        }
        // This requires an explicit Termux-shell connector entry point, not merely a generic shell tool.
        if ("termux_shell" in tools && "termux_command" in tools) {
            caps += "shell.execute"
        }
        return caps
    }
}
