package com.phoneai.app

/**
 * Local, fail-closed guard for high-risk financial apps and credential/card text.
 * This is a safety boundary, not a substitute for Android's own authentication.
 */
object SafetyGate {
    private val sensitiveAppTokens = listOf(
        "bank", "banking", "payment", "wallet", "paypal", "cash app", "venmo",
        "wise", "revolut", "stripe", "monzo", "chime", "paytm", "phonepe",
        "google pay", "gpay", "apple pay", "samsung pay", "flutterwave",
        "opay", "palmpay", "kuda", "moniepoint", "paga", "stanbic",
        "ecobank", "access bank", "gtbank", "zenith", "uba", "firstbank",
        "fidelity bank", "sterling bank", "union bank", "wema", "fcmb",
        "polaris", "providus", "keystone"
    )

    private val sensitivePackageTokens = listOf(
        "bank", "banking", "payment", "wallet", "paypal", "venmo", "wise",
        "revolut", "monzo", "cashapp" // package check is token-based; label check is stricter
    )

    private val credentialText = Regex(
        "(?i)\\b(card|cvv|cvc|password|passcode|security\\s+code|card\\s+number|credit\\s+card|debit\\s+card|one[- ]time\\s+(?:password|code)|otp|pin|recovery\\s+phrase|seed\\s+phrase|private\\s+key|access\\s+token|api\\s+key|bank\\s+account|routing\\s+number|social\\s+security|national\\s+id|passport)\\b"
    )
    private val sensitiveBrowserField = Regex(
        "(?i)(password|passcode|pin|otp|one[- ]time|verification|security\\s+code|recovery|seed\\s+phrase|private\\s+key|api\\s+key|access\\s+token|card|cvv|cvc|bank\\s+account|routing\\s+number|national\\s+id|passport)"
    )
    private val longDigitRun = Regex("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)")

    fun isSensitiveApp(nameOrPackage: String?, label: String? = null): Boolean {
        val name = nameOrPackage.orEmpty().lowercase().replace('_', ' ')
        val appLabel = label.orEmpty().lowercase()
        if (sensitiveAppTokens.any { name.contains(it) || appLabel.contains(it) }) return true
        val pkg = nameOrPackage.orEmpty().lowercase()
        return sensitivePackageTokens.any { token -> pkg.contains(token.replace(" ", "")) }
    }

    fun containsSensitiveCredentialText(text: String?): Boolean {
        val value = text.orEmpty()
        return credentialText.containsMatchIn(value) || longDigitRun.containsMatchIn(value)
    }

    fun blockReason(tool: String, target: String?, text: String?, currentPackage: String?, currentLabel: String?): String? {
        if (tool == "open_app" && isSensitiveApp(target, target)) {
            return "Orion's safety gate blocks opening or automating banking and payment apps. Use the bank's own app manually."
        }
        if (tool in setOf("type", "browser_type") && containsSensitiveCredentialText(text)) {
            return "Orion's safety gate blocks typing text that appears to contain credentials, payment details, identity numbers, or secrets. Enter sensitive details yourself."
        }
        if (tool == "browser_type" && sensitiveBrowserField.containsMatchIn(target.orEmpty())) {
            return "Orion's safety gate blocks filling password, verification, recovery, payment, identity, or secret fields. Complete that field yourself."
        }
        if (tool == "open_deep_link" && containsSensitiveCredentialText(target)) {
            return "Orion's safety gate blocks deep links containing credential-like or sensitive numeric data."
        }
        val readOnly = setOf("read_screen", "verify_action", "validate_action", "browser_snapshot", "browser_verify", "read_notifications")
        if (tool !in readOnly && isSensitiveApp(currentPackage, currentLabel)) {
            return "Orion's safety gate blocks automation inside banking and payment apps. Leave that app to continue."
        }
        return null
    }
}
