package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small local registry for reviewed, declarative PhoneAI skills.
 *
 * A skill is metadata and a constrained list of existing tool names, never executable code.
 * The registry does not download skills, grant Android permissions, or invoke tools itself.
 * The agent must call authorize() immediately before every tool call.
 */
data class PhoneAiSkill(
    val id: String,
    val version: String,
    val description: String,
    val allowedTools: Set<String>,
    val maxCalls: Int = 6,
    val maxDurationMs: Long = 30_000,
    val requiresApproval: Boolean = false,
    val enabled: Boolean = false
)

data class SkillDecision(val allowed: Boolean, val reason: String)

object SkillRegistry {
    private const val PREFS = "phoneai_skill_registry_v1"
    private const val KEY_SKILLS = "skills_json"

    // These are existing, bounded tools only. A skill cannot define a new tool or execute code.
    private val supportedTools = setOf(
        "time", "battery", "device_info", "list_apps", "read_screen",
        "web_search", "fetch_url", "research_web", "github_public", "integration_status", "telegram_status", "mcp_status", "mcp_list_tools", "open_url", "recall", "remember",
        "wait", "wait_for", "click_text", "scroll", "open_app",
        "task_list", "task_add", "task_update", "task_delete"
    )

    // Consequential tools always require approval, regardless of a skill's manifest setting.
    private val consequentialTools = setOf(
        "type", "sms", "dial", "copy", "record_screen", "settings",
        "forget", "update_memory", "open_url", "task_delete"
    )

    private val idPattern = Regex("^[a-z0-9][a-z0-9._-]{1,47}$")
    private val versionPattern = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$")

    @Synchronized
    fun list(context: Context): List<PhoneAiSkill> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SKILLS, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                parse(array.optJSONObject(index))
            }.sortedBy { it.id }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Register reviewed metadata. New skills are disabled by default.
     * Unknown tools, invalid IDs/versions, excessive budgets, and duplicate IDs are rejected.
     */
    @Synchronized
    fun register(context: Context, candidate: PhoneAiSkill): SkillDecision {
        val validated = validate(candidate)
        if (!validated.allowed) return validated
        val current = list(context)
        if (current.any { it.id == candidate.id }) return SkillDecision(false, "A skill with this ID already exists.")
        return save(context, current + candidate.copy(enabled = false))
    }

    @Synchronized
    fun setEnabled(context: Context, id: String, enabled: Boolean): SkillDecision {
        val current = list(context)
        if (current.none { it.id == id }) return SkillDecision(false, "Skill not found.")
        val updated = current.map { if (it.id == id) it.copy(enabled = enabled) else it }
        return save(context, updated)
    }

    @Synchronized
    fun remove(context: Context, id: String): SkillDecision {
        val current = list(context)
        if (current.none { it.id == id }) return SkillDecision(false, "Skill not found.")
        return save(context, current.filterNot { it.id == id })
    }

    /**
     * Must be checked before each tool call. callsUsed is the count already made by this skill
     * in the current run; elapsedMs is the elapsed time for that run.
     */
    fun authorize(
        context: Context,
        skillId: String,
        toolName: String,
        callsUsed: Int,
        elapsedMs: Long,
        userApprovedThisRun: Boolean
    ): SkillDecision {
        val skill = list(context).firstOrNull { it.id == skillId }
            ?: return SkillDecision(false, "Skill is not registered.")
        if (!skill.enabled) return SkillDecision(false, "Skill is disabled.")
        if (toolName !in supportedTools || toolName !in skill.allowedTools) {
            return SkillDecision(false, "This skill is not allowed to use that tool.")
        }
        if (callsUsed < 0 || callsUsed >= skill.maxCalls) {
            return SkillDecision(false, "Skill call budget reached.")
        }
        if (elapsedMs < 0 || elapsedMs > skill.maxDurationMs) {
            return SkillDecision(false, "Skill time budget reached.")
        }
        if ((skill.requiresApproval || toolName in consequentialTools) && !userApprovedThisRun) {
            return SkillDecision(false, "User approval is required before this action.")
        }
        return SkillDecision(true, "Allowed.")
    }

    private fun validate(skill: PhoneAiSkill): SkillDecision {
        if (!idPattern.matches(skill.id)) return SkillDecision(false, "Skill ID must be 2–48 lowercase letters, numbers, dots, underscores, or hyphens.")
        if (!versionPattern.matches(skill.version)) return SkillDecision(false, "Version must use MAJOR.MINOR.PATCH format.")
        if (skill.description.isBlank() || skill.description.length > 500) return SkillDecision(false, "Description must be 1–500 characters.")
        if (skill.allowedTools.isEmpty()) return SkillDecision(false, "A skill must declare at least one allowed tool.")
        if (skill.allowedTools.any { it !in supportedTools }) return SkillDecision(false, "Skill contains an unsupported tool.")
        if (skill.maxCalls !in 1..20) return SkillDecision(false, "Skill call budget must be between 1 and 20.")
        if (skill.maxDurationMs !in 1_000L..120_000L) return SkillDecision(false, "Skill duration budget must be between 1 and 120 seconds.")
        return SkillDecision(true, "Valid.")
    }

    private fun save(context: Context, skills: List<PhoneAiSkill>): SkillDecision {
        val array = JSONArray()
        skills.forEach { skill ->
            array.put(
                JSONObject()
                    .put("id", skill.id)
                    .put("version", skill.version)
                    .put("description", skill.description)
                    .put("allowedTools", JSONArray(skill.allowedTools.sorted()))
                    .put("maxCalls", skill.maxCalls)
                    .put("maxDurationMs", skill.maxDurationMs)
                    .put("requiresApproval", skill.requiresApproval)
                    .put("enabled", skill.enabled)
            )
        }
        val ok = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SKILLS, array.toString()).commit()
        return if (ok) SkillDecision(true, "Saved.") else SkillDecision(false, "Could not save skill registry.")
    }

    private fun parse(obj: JSONObject?): PhoneAiSkill? {
        if (obj == null) return null
        return try {
            val toolsJson = obj.optJSONArray("allowedTools") ?: JSONArray()
            val tools = (0 until toolsJson.length()).mapNotNull { toolsJson.optString(it).takeIf(String::isNotBlank) }.toSet()
            val skill = PhoneAiSkill(
                id = obj.getString("id"),
                version = obj.getString("version"),
                description = obj.getString("description"),
                allowedTools = tools,
                maxCalls = obj.optInt("maxCalls", 6),
                maxDurationMs = obj.optLong("maxDurationMs", 30_000L),
                requiresApproval = obj.optBoolean("requiresApproval", false),
                enabled = obj.optBoolean("enabled", false)
            )
            if (validate(skill).allowed) skill else null
        } catch (_: Exception) {
            null
        }
    }
}
