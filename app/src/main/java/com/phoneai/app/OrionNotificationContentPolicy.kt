package com.phoneai.app

/** Excludes credential, financial and security notifications from Orion's in-memory message context. */
internal object OrionNotificationContentPolicy {
    private val sensitivePackageTokens = setOf("bank", "finance", "wallet", "payment", "pay", "authenticator", "password", "credential")
    private val sensitiveText = Regex(
        """\b(otp|one[- ]time (password|code)|verification code|security code|passcode|password|new login|new sign[- ]in|authentication|2fa|two[- ]factor|recovery code|backup code|passkey|transaction (alert|receipt)|bank transfer|card (number|payment)|cvv|pin code|payment received|money received|wallet balance)\b""",
        RegexOption.IGNORE_CASE
    )

    fun isSafe(packageName: String, appLabel: String, title: String, text: String): Boolean {
        val pkg = packageName.lowercase()
        val label = appLabel.lowercase()
        if (sensitivePackageTokens.any { pkg.contains(it) || label.contains(it) }) return false
        if (sensitiveText.containsMatchIn(title) || sensitiveText.containsMatchIn(text)) return false
        return true
    }
}