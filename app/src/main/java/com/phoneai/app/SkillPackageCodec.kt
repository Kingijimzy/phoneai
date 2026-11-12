package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Portable declarative skill metadata. Imported skills use Orion's shared registered tool
 * inventory; package-supplied tool lists and approval flags are legacy fields, not permissions.
 */
internal object SkillPackageCodec {
    private const val FORMAT = "phoneai-skill"
    private const val FORMAT_VERSION = 1

    fun export(skill: PhoneAiSkill): String = JSONObject()
        .put("format", FORMAT)
        .put("formatVersion", FORMAT_VERSION)
        .put("skill", JSONObject()
            .put("id", skill.id)
            .put("version", skill.version)
            .put("description", skill.description)
            .put("allowedTools", JSONArray(ToolRegistry.names().sorted()))
            .put("maxCalls", skill.maxCalls)
            .put("maxDurationMs", skill.maxDurationMs)
            .put("requiresApproval", false)
        ).toString(2)

    fun parse(json: String): Pair<PhoneAiSkill?, String> {
        if (json.isBlank() || json.length > 100_000) return null to "Skill package is empty or too large."
        return try {
            val root = JSONObject(json)
            if (root.optString("format") != FORMAT) return null to "Not a PhoneAI skill package."
            if (root.optInt("formatVersion", -1) != FORMAT_VERSION) return null to "Unsupported skill package version."
            val obj = root.optJSONObject("skill") ?: return null to "Skill package is missing its skill definition."
            val candidate = PhoneAiSkill(
                id = obj.optString("id", ""),
                version = obj.optString("version", ""),
                description = obj.optString("description", ""),
                allowedTools = ToolRegistry.names(),
                maxCalls = obj.optInt("maxCalls", 6),
                maxDurationMs = obj.optLong("maxDurationMs", 30_000L),
                requiresApproval = false,
                enabled = false
            )
            val check = SkillRegistry.validate(candidate)
            if (!check.allowed) null to check.reason else candidate to "Valid package. Imported skills use Orion's shared registered tools."
        } catch (_: Exception) {
            null to "Could not parse the skill package. Check that it is valid JSON."
        }
    }
}
