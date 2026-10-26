package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionSkillInstallPolicyTest {
    private fun manifest(
        kind: OrionSkillInstallPolicy.PayloadKind = OrionSkillInstallPolicy.PayloadKind.DECLARATIVE_MANIFEST,
        trust: OrionSkillInstallPolicy.SourceTrust = OrionSkillInstallPolicy.SourceTrust.OFFICIAL_PROVIDER,
        signature: Boolean = false
    ) = OrionSkillInstallPolicy.Manifest(
        id = "gmail.reader",
        version = "1.0.0",
        sourceTrust = trust,
        payloadKind = kind,
        requestedCapabilities = setOf("gmail.messages.read"),
        description = "Read Gmail messages using the registered Gmail connector.",
        signatureVerified = signature
    )

    @Test
    fun declarativeSkillRequiresExplicitApproval() {
        val review = OrionSkillInstallPolicy.review(manifest(), setOf("gmail.messages.read"), false)
        assertEquals(OrionSkillInstallPolicy.ReviewStatus.NEEDS_USER_APPROVAL, review.status)
        assertFalse(review.canExecuteNow)
    }

    @Test
    fun approvedDeclarativeSkillCanBindOnlyRegisteredCapability() {
        val review = OrionSkillInstallPolicy.review(manifest(), setOf("gmail.messages.read"), true)
        assertEquals(OrionSkillInstallPolicy.ReviewStatus.APPROVED, review.status)
        assertTrue(review.canExecuteNow)
    }

    @Test
    fun missingCapabilityCannotBeInventedByManifest() {
        val review = OrionSkillInstallPolicy.review(manifest(), emptySet(), true)
        assertEquals(OrionSkillInstallPolicy.ReviewStatus.NEEDS_USER_APPROVAL, review.status)
        assertTrue(review.missingCapabilities.contains("gmail.messages.read"))
        assertFalse(review.canExecuteNow)
    }

    @Test
    fun arbitraryShellCodeIsRejectedAsSkill() {
        val review = OrionSkillInstallPolicy.review(
            manifest(kind = OrionSkillInstallPolicy.PayloadKind.SHELL_SCRIPT),
            setOf("gmail.messages.read"),
            true
        )
        assertEquals(OrionSkillInstallPolicy.ReviewStatus.REJECTED, review.status)
        assertFalse(review.canExecuteNow)
    }

    @Test
    fun unverifiedRepositoryPackageIsRejected() {
        val review = OrionSkillInstallPolicy.review(
            manifest(trust = OrionSkillInstallPolicy.SourceTrust.VERIFIED_REPOSITORY),
            setOf("gmail.messages.read"),
            true
        )
        assertEquals(OrionSkillInstallPolicy.ReviewStatus.REJECTED, review.status)
    }
}
