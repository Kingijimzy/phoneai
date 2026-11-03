package com.phoneai.app

/** Conservative classifier for explicit tool failure results; never treats a normal result as success proof. */
internal object ToolResultPolicy {
    enum class FailureKind {
        PERMISSION, MISSING_DEPENDENCY, TIMEOUT, NETWORK_OR_PROVIDER,
        INVALID_OUTPUT, UNSUPPORTED, DENIED_OR_BLOCKED, EMPTY_RESULT, UNKNOWN
    }
    data class Diagnosis(val kind: FailureKind, val reason: String, val safeNextStep: String)

    /** Classify returned failure text; a guess is never proof or permission to retry. */
    fun diagnose(result: String): Diagnosis {
        val clean = result.trim().take(500)
        val lower = clean.lowercase()
        val kind = when {
            lower.isEmpty() -> FailureKind.EMPTY_RESULT
            listOf("permission", "accessibility service", "not granted", "consent required", "securityexception").any(lower::contains) -> FailureKind.PERMISSION
            listOf("not installed", "missing dependency", "not available on device", "command not found", "no such file or directory").any(lower::contains) -> FailureKind.MISSING_DEPENDENCY
            listOf("timed out", "timeout", "deadline exceeded", "took too long").any(lower::contains) -> FailureKind.TIMEOUT
            listOf("http 429", "rate limit", "quota exceeded", "provider", "network", "connection refused", "unreachable", "http 5", "503", "502", "504").any(lower::contains) -> FailureKind.NETWORK_OR_PROVIDER
            listOf("invalid json", "malformed", "parse error", "invalid output", "unexpected format", "empty response").any(lower::contains) -> FailureKind.INVALID_OUTPUT
            listOf("unsupported", "not supported").any(lower::contains) -> FailureKind.UNSUPPORTED
            listOf("denied", "blocked", "not approved", "user did not approve", "outside task scope", "refusing").any(lower::contains) -> FailureKind.DENIED_OR_BLOCKED
            else -> FailureKind.UNKNOWN
        }
        val advice = when (kind) {
            FailureKind.PERMISSION -> "Open Orion's Permissions/Accessibility settings, grant only the required access, then verify the current screen before continuing."
            FailureKind.MISSING_DEPENDENCY -> "Check the named feature pack or bundled dependency; do not switch to an unapproved external shell or install unknown binaries."
            FailureKind.TIMEOUT -> "Check whether the action already took effect, wait for the page/device to settle, then retry only after the current state is known."
            FailureKind.NETWORK_OR_PROVIDER -> "Check connection, quota and provider status; preserve the checkpoint and retry later or choose a configured healthy provider."
            FailureKind.INVALID_OUTPUT -> "Discard the malformed result and request a fresh, complete response; do not execute partial output."
            FailureKind.UNSUPPORTED -> "Choose a supported tool or perform the step manually; do not pretend the unsupported action ran."
            FailureKind.DENIED_OR_BLOCKED -> "Keep the action blocked. Review the exact permission, pack or approval requirement; never bypass a DENY decision."
            FailureKind.EMPTY_RESULT -> "Gather fresh state/evidence before deciding whether the action succeeded or should be attempted again."
            FailureKind.UNKNOWN -> "Inspect the recorded result and current device state; choose a different safe method only when the outcome is known."
        }
        return Diagnosis(kind, clean.ifBlank { "The tool returned an empty result." }, advice)
    }

    fun failureReason(result: String): String? {
        val clean = result.trim()
        if (clean.isEmpty()) return "The tool returned an empty result."
        val prefixes = listOf("error", "failed", "failure", "denied", "blocked", "timed out", "timeout", "cancelled", "canceled")
        val match = prefixes.firstOrNull { clean.startsWith(it, ignoreCase = true) } ?: return null
        return diagnose(clean).reason.take(220).ifBlank { "Tool reported $match." }
    }
}
