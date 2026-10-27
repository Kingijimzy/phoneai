package com.phoneai.app

/**
 * Guardrail for Orion's self-extension workflow.
 *
 * Downloaded plugins are proposals, not executable trust. Only declarative manifests that bind
 * already-registered native tools may be enabled after explicit user approval.
 * Native code, shell scripts, APKs, browser extensions, and arbitrary URLs are never run as skills.
 */
internal object OrionSkillInstallPolicy {
    enum class SourceTrust { OFFICIAL_PROVIDER, VERIFIED_REPOSITORY, UNKNOWN }
    enum class PayloadKind { DECLARATIVE_MANIFEST, KOTLIN_OR_JAVA_CODE, SHELL_SCRIPT, APK, BROWSER_EXTENSION, UNKNOWN }
    enum class ReviewStatus { APPROVED, NEEDS_USER_APPROVAL, REJECTED }

    data class Manifest(
        val id: String,
        val version: String,
        val sourceTrust: SourceTrust,
        val payloadKind: PayloadKind,
        val requestedCapabilities: Set<String>,
        val description: String,
        val signatureVerified: Boolean = false
    )

    data class Review(
        val status: ReviewStatus,
        val missingCapabilities: Set<String>,
        val reasons: List<String>,
        val requiresUserApproval: Boolean,
        val canExecuteNow: Boolean
    )

    private val idPattern = Regex("^[a-z][a-z0-9_.-]{2,63}$")
    private val versionPattern = Regex("^[0-9]+\\.[0-9]+(?:\\.[0-9]+)?(?:[-+][A-Za-z0-9.-]+)?$")

    fun review(
        manifest: Manifest,
        registeredCapabilities: Set<String>,
        userApproved: Boolean
    ): Review {
        val reasons = mutableListOf<String>()
        if (!idPattern.matches(manifest.id)) reasons += "Invalid skill identifier."
        if (!versionPattern.matches(manifest.version)) reasons += "Version must be explicit and pinned."
        if (manifest.description.isBlank() || manifest.description.length > 500) reasons += "Description is missing or too long."
        if (manifest.requestedCapabilities.isEmpty()) reasons += "Skill must declare the capabilities it needs."
        if (manifest.requestedCapabilities.any { !Regex("^[a-z][a-z0-9_.-]{1,79}$").matches(it) }) {
            reasons += "One or more requested capability names are invalid."
        }
        if (manifest.payloadKind != PayloadKind.DECLARATIVE_MANIFEST) {
            reasons += "Executable payloads are not installable as skills; use reviewed native app code and a signed app build."
        }
        if (manifest.sourceTrust == SourceTrust.UNKNOWN) reasons += "Skill source is unverified."
        if (manifest.sourceTrust == SourceTrust.VERIFIED_REPOSITORY && !manifest.signatureVerified) {
            reasons += "Repository package signature or pinned integrity hash is not verified."
        }
        val missing = manifest.requestedCapabilities - registeredCapabilities
        if (missing.isNotEmpty()) {
            // Approval cannot make an unknown capability safe or registered. Reject instead of
            // misleading the user into thinking approval alone can grant a new tool.
            reasons += "Required capabilities are not registered: " + missing.sorted().joinToString(", ")
        }

        val permanentlyRejected = reasons.any {
            it.startsWith("Invalid") ||
                it.startsWith("Version") ||
                it.startsWith("Description") ||
                it.startsWith("Skill must") ||
                it.startsWith("One or more") ||
                it.startsWith("Executable") ||
                it.startsWith("Skill source") ||
                it.startsWith("Repository") ||
                it.startsWith("Required capabilities")
        }
        if (permanentlyRejected) {
            return Review(ReviewStatus.REJECTED, missing, reasons, false, false)
        }
        if (!userApproved) {
            reasons += "User approval is required before enabling this skill."
            return Review(ReviewStatus.NEEDS_USER_APPROVAL, emptySet(), reasons, true, false)
        }
        return Review(ReviewStatus.APPROVED, emptySet(), reasons, false, true)
    }
}
