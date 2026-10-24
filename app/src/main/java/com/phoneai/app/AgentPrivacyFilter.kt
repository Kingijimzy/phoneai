package com.phoneai.app

/**
 * Conservative redaction for task-recovery metadata.
 * Pure helper so privacy behavior can be tested without Android dependencies.
 */
internal object AgentPrivacyFilter {
    private const val MAX_COMMAND = 2000

    fun safeCommand(command: String): String = command.trim()
        .replace(Regex("(?i)\\b(bearer\\s+)[a-z0-9._~+/-]+=*")) { it.groupValues[1] + "[redacted]" }
        .replace(Regex("(?i)\\b(api[_ -]?key|access[_ -]?token|refresh[_ -]?token|password|passwd|passcode|pin|secret|authorization)\\b\\s*[:=]\\s*[^\\s,;]+")) { it.groupValues[1] + "=[redacted]" }
        .replace(Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE), "[email redacted]")
        .replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), "[URL redacted]")
        .replace(Regex("(?<![A-Za-z0-9])\\+?\\d[\\d ()-]{6,}\\d(?![A-Za-z0-9])"), "[number redacted]")
        .take(MAX_COMMAND)
}
