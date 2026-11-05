package com.phoneai.app

import android.content.Context
import org.json.JSONObject

/**
 * Owns registered-skill discovery, validation and per-tool execution authorization.
 *
 * Skills are declarative manifests over existing tools, never executable code. Creating or
 * enabling one does not grant permissions: every actual tool call is re-authorized against
 * the live registry, per-tool owner settings, central risk gate and bounded run budget.
 */
internal object OrionSkillsCapabilityExecutor {
    private val supportedTools = setOf("skills_list", "skill_create", "skill_enable", "skill_disable")

    data class Run(val skillId: String, val startedAt: Long, val callsUsed: Int = 0)

    fun supports(tool: String): Boolean = tool in supportedTools

    fun execute(tool: String, args: JSONObject, context: Context): String = when (tool) {
        "skills_list" -> listText(context)
        "skill_create" -> create(context, args)
        "skill_enable" -> setEnabled(context, args.optString("id"), true)
        "skill_disable" -> setEnabled(context, args.optString("id"), false)
        else -> "error: skills executor does not implement '$tool'."
    }

    fun listText(context: Context): String {
        val skills = SkillRegistry.list(context)
        if (skills.isEmpty()) return "No skills are registered yet. Use skill_create to create a declarative workflow over existing tools."
        return skills.joinToString("\n") { skill ->
            "${skill.id} [${if (skill.enabled) "ENABLED" else "DISABLED"}] v${skill.version}: ${skill.description} | tools: ${skill.allowedTools.sorted().joinToString(", ")} | approval required: ${skill.requiresApproval}"
        }
    }

    private fun create(context: Context, args: JSONObject): String {
        val tools = args.optJSONArray("allowed_tools") ?: args.optJSONArray("allowedTools")
            ?: return "error: allowed_tools must be a JSON array of existing tool IDs."
        val allowedTools = (0 until tools.length())
            .mapNotNull { index -> tools.optString(index).trim().takeIf { it.isNotBlank() } }
            .toSet()
        val candidate = PhoneAiSkill(
            id = args.optString("id").trim(),
            version = args.optString("version", "1.0.0").trim(),
            description = args.optString("description").trim(),
            allowedTools = allowedTools,
            maxCalls = args.optInt("max_calls", args.optInt("maxCalls", 6)),
            maxDurationMs = args.optLong("max_duration_ms", args.optLong("maxDurationMs", 30_000L)),
            requiresApproval = args.optBoolean("requires_approval", args.optBoolean("requiresApproval", false)),
            enabled = false
        )
        val registered = SkillRegistry.register(context, candidate)
        if (!registered.allowed) return "error: skill was not created: ${registered.reason}"
        // A skill is only declarative metadata over existing tools. Registry validation checks
        // every tool ID and budget; actual write actions still require owner approval at dispatch.
        val enabled = SkillRegistry.setEnabled(context, candidate.id, true)
        if (!enabled.allowed) {
            return "Skill draft ${candidate.id} was saved but remains disabled: ${enabled.reason}"
        }
        return "Created and auto-enabled validated skill ${candidate.id} v${candidate.version}. It can use only these existing tools: ${candidate.allowedTools.sorted().joinToString(", ")}. No new Android permissions or native capabilities were granted; every write/consequential tool call still passes the normal owner and risk gates."
    }

    private fun setEnabled(context: Context, id: String, enabled: Boolean): String {
        if (id.isBlank()) return "error: skill ID is required."
        val result = SkillRegistry.setEnabled(context, id, enabled)
        return if (result.allowed) {
            if (enabled) "Skill $id enabled. This changes no tool permissions; every tool call still passes the central dispatcher and skill authorization policy."
            else "Skill $id disabled."
        } else "error: could not ${if (enabled) "enable" else "disable"} skill $id: ${result.reason}"
    }

    fun list(context: Context): List<PhoneAiSkill> = SkillRegistry.list(context)

    fun discover(context: Context, request: String, limit: Int = 4): List<PhoneAiSkill> =
        SkillRegistry.discover(context, request, limit)

    fun findEnabled(context: Context, id: String): PhoneAiSkill? =
        SkillRegistry.list(context).firstOrNull { it.id == id && it.enabled }

    fun validate(skill: PhoneAiSkill): SkillDecision = SkillRegistry.validate(skill)

    fun beginRun(context: Context, id: String, now: Long = System.currentTimeMillis()): Run? =
        findEnabled(context, id)?.let { Run(it.id, now) }

    fun authorize(
        context: Context,
        run: Run,
        tool: String,
        userApprovedThisCall: Boolean
    ): SkillDecision = SkillRegistry.authorize(
        context = context,
        skillId = run.skillId,
        toolName = tool,
        callsUsed = run.callsUsed,
        elapsedMs = (System.currentTimeMillis() - run.startedAt).coerceAtLeast(0L),
        userApprovedThisRun = userApprovedThisCall
    )

    fun nextCall(run: Run): Run = run.copy(callsUsed = run.callsUsed + 1)
}
