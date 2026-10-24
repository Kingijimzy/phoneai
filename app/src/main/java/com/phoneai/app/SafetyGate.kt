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
        "revolut", "monzo", "cashapp", "googlequicksearchbox" // package check is token-based; label check is stricter
    )

    private val credentialText = Regex(
        "(?i)\\b(card|cvv|cvc|password|passcode|security\\s+code|card\\s+number|credit\\s+card|debit\\s+card|one[- ]time\\s+(?:password|code)|otp|pin)\\b"
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
        if (tool == "type" && containsSensitiveCredentialText(text)) {
            return "Orion's safety gate blocks typing card, CVV/CVC, password, passcode, PIN, OTP, security-code, or card-number text. Enter sensitive details yourself."
        }
        val readOnly = setOf("read_screen", "verify_action", "validate_action", "browser_snapshot", "browser_verify")
        if (tool !in readOnly && isSensitiveApp(currentPackage, currentLabel)) {
            return "Orion's safety gate blocks automation inside banking and payment apps. Leave that app to continue."
        }
        return null
    }
}
