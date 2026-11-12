package com.phoneai.app

/** Validation for declarative skill metadata over Orion's shared registered tool inventory. */
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

    @Suppress("UNUSED_PARAMETER")
    fun review(
        manifest: Manifest,
        registeredCapabilities: Set<String>,
        userApproved: Boolean
    ): Review {
        val reasons = mutableListOf<String>()
        if (!idPattern.matches(manifest.id)) reasons += "Invalid skill identifier."
        if (!versionPattern.matches(manifest.version)) reasons += "Version must be explicit and pinned."
        if (manifest.description.isBlank() || manifest.description.length > 500) reasons += "Description is missing or too long."
        if (manifest.requestedCapabilities.isEmpty()) reasons += "Skill must use Orion's registered tool inventory."
        if (manifest.payloadKind != PayloadKind.DECLARATIVE_MANIFEST) {
            reasons += "Only declarative metadata is supported by the skill installer."
        }
        val missing = manifest.requestedCapabilities - registeredCapabilities
        if (missing.isNotEmpty()) {
            reasons += "Required capabilities are not registered: " + missing.sorted().joinToString(", ")
        }
        val rejected = reasons.isNotEmpty()
        return if (rejected) {
            Review(ReviewStatus.REJECTED, missing, reasons, false, false)
        } else {
            Review(ReviewStatus.APPROVED, emptySet(), emptyList(), false, true)
        }
    }
}
