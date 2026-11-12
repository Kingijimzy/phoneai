package com.phoneai.app

import android.content.Context
import org.json.JSONObject

/**
 * Registers declarative metadata over Orion's shared registered tool inventory.
 * No per-skill tool scopes or approval prompts are added.
 */
internal object DeclarativeSkillInstaller {
    data class Outcome(
        val status: OrionSkillInstallPolicy.ReviewStatus,
        val message: String,
        val skillId: String? = null
    )

    @Suppress("UNUSED_PARAMETER")
    fun installManifest(
        context: Context,
        json: String,
        approved: Boolean,
        verifiedSource: OrionSkillInstallPolicy.SourceTrust = OrionSkillInstallPolicy.SourceTrust.UNKNOWN,
        signatureVerified: Boolean = false
    ): Outcome {
        if (json.length !in 2..16_000) {
            return Outcome(OrionSkillInstallPolicy.ReviewStatus.REJECTED, "Manifest size is invalid.")
        }
        val obj = try {
            JSONObject(json)
        } catch (_: Exception) {
            return Outcome(OrionSkillInstallPolicy.ReviewStatus.REJECTED, "Manifest is not valid JSON.")
        }
        val allowedKeys = setOf("id", "version", "description", "payloadKind", "allowedTools")
        val keys = obj.keys()
        while (keys.hasNext()) {
            if (keys.next() !in allowedKeys) {
                return Outcome(OrionSkillInstallPolicy.ReviewStatus.REJECTED, "Manifest contains an unsupported field.")
            }
        }
        if (obj.optString("payloadKind") != "declarative") {
            return Outcome(OrionSkillInstallPolicy.ReviewStatus.REJECTED, "Only declarative metadata is supported.")
        }
        val sharedTools = ToolRegistry.names().toSet()
        val manifest = OrionSkillInstallPolicy.Manifest(
            id = obj.optString("id"),
            version = obj.optString("version"),
            sourceTrust = OrionSkillInstallPolicy.SourceTrust.UNKNOWN,
            payloadKind = OrionSkillInstallPolicy.PayloadKind.DECLARATIVE_MANIFEST,
            requestedCapabilities = sharedTools,
            description = obj.optString("description"),
            signatureVerified = false
        )
        val review = OrionSkillInstallPolicy.review(manifest, sharedTools, userApproved = false)
        if (review.status != OrionSkillInstallPolicy.ReviewStatus.APPROVED) {
            return Outcome(review.status, review.reasons.joinToString(" "), manifest.id)
        }
        val version = manifest.version.let {
            if (it.count { c -> c == '.' } == 1) "$it.0"
            else it.substringBefore('+').substringBefore('-')
        }
        val registration = SkillRegistry.register(
            context,
            PhoneAiSkill(
                id = manifest.id,
                version = version,
                description = manifest.description,
                allowedTools = sharedTools,
                maxCalls = 6,
                maxDurationMs = 30_000L,
                requiresApproval = false,
                enabled = true
            )
        )
        return if (registration.allowed) {
            Outcome(OrionSkillInstallPolicy.ReviewStatus.APPROVED, "Declarative skill registered and enabled with shared tools.", manifest.id)
        } else {
            Outcome(OrionSkillInstallPolicy.ReviewStatus.REJECTED, registration.reason, manifest.id)
        }
    }
}
