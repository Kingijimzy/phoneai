package com.phoneai.app

import android.content.Context
import org.json.JSONObject

/**
 * Installs declarative skill proposals only. It deliberately accepts no script, APK, URL loader,
 * or code field. The caller must collect a separate user confirmation before passing approved=true.
 */
internal object DeclarativeSkillInstaller {
    data class Outcome(
        val status: OrionSkillInstallPolicy.ReviewStatus,
        val message: String,
        val skillId: String? = null
    )

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

        // Strict schema: unknown keys are rejected so a hidden code/script field cannot be ignored.
        val allowedKeys = setOf("id", "version", "description", "allowedTools", "payloadKind")
        val keys = obj.keys()
        while (keys.hasNext()) {
            if (keys.next() !in allowedKeys) {
                return Outcome(OrionSkillInstallPolicy.ReviewStatus.REJECTED, "Manifest contains an unsupported field.")
            }
        }
        if (obj.optString("payloadKind") != "declarative") {
            return Outcome(OrionSkillInstallPolicy.ReviewStatus.REJECTED, "Only declarative manifests are supported; executable payloads are forbidden.")
        }
        val toolsJson = obj.optJSONArray("allowedTools")
            ?: return Outcome(OrionSkillInstallPolicy.ReviewStatus.REJECTED, "allowedTools must be an array.")
        if (toolsJson.length() !in 1..20) {
            return Outcome(OrionSkillInstallPolicy.ReviewStatus.REJECTED, "Declare 1–20 registered tools.")
        }
        val requested = (0 until toolsJson.length()).map { toolsJson.optString(it) }.toSet()
        if (requested.size != toolsJson.length()) {
            return Outcome(OrionSkillInstallPolicy.ReviewStatus.REJECTED, "Duplicate tool names are not allowed.")
        }

        val manifest = OrionSkillInstallPolicy.Manifest(
            id = obj.optString("id"),
            version = obj.optString("version"),
            sourceTrust = verifiedSource,
            payloadKind = OrionSkillInstallPolicy.PayloadKind.DECLARATIVE_MANIFEST,
            requestedCapabilities = requested,
            description = obj.optString("description"),
            signatureVerified = signatureVerified
        )
        val review = OrionSkillInstallPolicy.review(manifest, ToolRegistry.names().toSet(), approved)
        if (review.status != OrionSkillInstallPolicy.ReviewStatus.APPROVED) {
            return Outcome(
                review.status,
                review.reasons.joinToString(" ").ifBlank { "User approval is required before enabling this skill." },
                manifest.id
            )
        }

        val registration = SkillRegistry.register(
            context,
            PhoneAiSkill(
                id = manifest.id,
                version = manifest.version.let {
                    if (it.count { c -> c == '.' } == 1) "$it.0"
                    else it.substringBefore('+').substringBefore('-')
                },
                description = manifest.description,
                allowedTools = requested,
                maxCalls = 6,
                maxDurationMs = 30_000L,
                requiresApproval = true,
                enabled = false
            )
        )
        if (!registration.allowed) {
            return Outcome(OrionSkillInstallPolicy.ReviewStatus.REJECTED, registration.reason, manifest.id)
        }

        // Registration starts disabled; explicit approved path activates the declarative skill.
        val enabled = SkillRegistry.setEnabled(context, manifest.id, true)
        return if (enabled.allowed) {
            Outcome(OrionSkillInstallPolicy.ReviewStatus.APPROVED, "Declarative skill registered and enabled after approval.", manifest.id)
        } else {
            Outcome(OrionSkillInstallPolicy.ReviewStatus.REJECTED, enabled.reason, manifest.id)
        }
    }
}
