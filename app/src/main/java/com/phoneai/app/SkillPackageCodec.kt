package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Portable, declarative PhoneAI skill package. It can reference existing registered tools only;
 * it is not executable code and cannot add Android permissions or bypass runtime authorization.
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
            .put("allowedTools", JSONArray(skill.allowedTools.sorted()))
            .put("maxCalls", skill.maxCalls)
            .put("maxDurationMs", skill.maxDurationMs)
            .put("requiresApproval", skill.requiresApproval)
        ).toString(2)

    fun parse(json: String): Pair<PhoneAiSkill?, String> {
        if (json.isBlank() || json.length > 100_000) return null to "Skill package is empty or too large."
        return try {
            val root = JSONObject(json)
            if (root.optString("format") != FORMAT) return null to "Not a PhoneAI skill package."
            if (root.optInt("formatVersion", -1) != FORMAT_VERSION) return null to "Unsupported skill package version."
            val obj = root.optJSONObject("skill") ?: return null to "Skill package is missing its skill definition."
            val toolsJson = obj.optJSONArray("allowedTools") ?: return null to "Skill package is missing allowedTools."
            if (toolsJson.length() !in 1..30) return null to "A skill must list 1–30 tools."
            val tools = (0 until toolsJson.length()).map { index ->
                if (toolsJson.isNull(index) || toolsJson.opt(index) !is String) return null to "Tool names must be strings."
                toolsJson.getString(index).trim()
            }
            if (tools.any { it.isBlank() } || tools.size != tools.toSet().size) return null to "Tool names must be non-empty and unique."
            val candidate = PhoneAiSkill(
                id = obj.optString("id", ""),
                version = obj.optString("version", ""),
                description = obj.optString("description", ""),
                allowedTools = tools.toSet(),
                maxCalls = obj.optInt("maxCalls", 6),
                maxDurationMs = obj.optLong("maxDurationMs", 30_000L),
                requiresApproval = obj.optBoolean("requiresApproval", true),
                enabled = false
            )
            val check = SkillRegistry.validate(candidate)
            if (!check.allowed) null to check.reason else candidate to "Valid package. Imported skills start disabled and must be reviewed."
        } catch (_: Exception) {
            null to "Could not parse the skill package. Check that it is valid JSON."
        }
    }
}
