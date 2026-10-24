package com.phoneai.app

/**
 * Local guard for high-risk financial apps and credential entry.
 * Ordinary words (for example "wallet" in a game, "transfer" in chat, or "trading")
 * are not enough by themselves to block normal reading and typing.
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
        "revolut", "monzo", "cashapp"
    )

    // Specific credential phrases only; generic "card", "pin", and "wallet" are intentionally excluded.
    private val credentialText = Regex(
        "(?i)\\b(cvv|cvc|security\\s+code|card\\s+number|credit\\s+card|debit\\s+card|one[- ]time\\s+(?:password|code)|(?:enter|type|confirm|verify|set|change|reset)\\s+(?:your\\s+)?(?:pin|pin\\s+code|password|passcode|otp)|(?:password|passcode|otp)\\s*(?:field|input)|(?:password|passcode|otp)\\s*[:=]|(?:pin|pin\\s+code)\\s+(?:for|to|of)\\s+(?:your\\s+)?(?:card|account|bank)|recovery\\s+phrase|seed\\s+phrase|private\\s+key|access\\s+token|api\\s+key|bank\\s+account(?:\\s+number)?|routing\\s+number|social\\s+security(?:\\s+number)?|national\\s+id|passport)\\b"
    )
    private val sensitiveBrowserField = Regex(
        "(?i)(password|passcode|otp|one[- ]time|verification|security\\s+code|recovery|seed\\s+phrase|private\\s+key|api\\s+key|access\\s+token|card\\s+number|credit\\s+card|debit\\s+card|cvv|cvc|bank\\s+account|routing\\s+number|national\\s+id|passport)"
    )
    private val sensitivePinField = Regex(
        "(?i)^\\s*(?:enter\\s+(?:your\\s+)?)?pin(?:\\s+code)?(?:\\s+field)?\\s*$|\\b(?:enter|type|confirm|verify|set|change|reset)\\s+(?:your\\s+)?pin(?:\\s+code)?\\b|\\bpin(?:\\s+code)?\\s+(?:for|to|of)\\s+(?:your\\s+)?(?:card|account|bank)\\b"
    )
    private val longDigitRun = Regex("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)")

    private val sensitiveActionMarkers = Regex(
        "(?i)\\b(bank\\s+transfer|transfer\\s+money|send\\s+money|make\\s+a\\s+payment|pay\\s+now|payment\\s+details|payment\\s+method|checkout|confirm\\s+(?:payment|transaction|purchase)|withdraw\\s+money|withdrawal\\s+amount|deposit\\s+money|place\\s+(?:a\\s+)?order|trade\\s+order|buy\\s+order|sell\\s+order|delete\\s+account|(?:enter|type|confirm|verify|reset|change|set)\\s+(?:your\\s+)?(?:pin|pin\\s+code|password|passcode|otp|verification\\s+code|security\\s+code)|(?:password|passcode|pin|otp)\\s+(?:field|required|incorrect|invalid)|one[- ]time\\s+code|verification\\s+code|security\\s+code|recovery\\s+phrase|seed\\s+phrase|private\\s+key|bank\\s+account|card\\s+number|cvv|cvc|publish\\s+now|post\\s+now)\\b"
    )

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

    /** Detect screens with explicit consequential-action or credential-entry context. */
    fun isSensitiveActionScreen(screenText: String?): Boolean {
        val value = screenText.orEmpty()
        return value.isNotBlank() && sensitiveActionMarkers.containsMatchIn(value)
    }

    fun blockReason(tool: String, target: String?, text: String?, currentPackage: String?, currentLabel: String?): String? {
        if (tool == "open_app" && isSensitiveApp(target, target)) {
            return "Orion's safety gate blocks opening or automating banking and payment apps. Use the bank's own app manually."
        }
        if (tool in setOf("type", "browser_type") && containsSensitiveCredentialText(text)) {
            return "Orion's safety gate blocks typing text that appears to contain credentials, payment details, identity numbers, or secrets. Enter sensitive details yourself."
        }
        if (tool == "browser_type" && (sensitiveBrowserField.containsMatchIn(target.orEmpty()) || sensitivePinField.containsMatchIn(target.orEmpty()))) {
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
