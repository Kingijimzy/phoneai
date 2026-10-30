package com.phoneai.app

import android.content.Context
import org.json.JSONObject

/**
 * Owns registered-skill discovery, validation and per-tool execution authorization.
 *
 * Skills are declarative manifests over existing registered tools, not downloaded code.
 * The Agent still owns the conversational task loop; every tool call made by that loop is
 * authorized here against the live registry, call budget, duration and approval policy.
 */
internal object OrionSkillsCapabilityExecutor {
    private val supportedTools = setOf("skills_list")

    data class Run(val skillId: String, val startedAt: Long, val callsUsed: Int = 0)

    fun supports(tool: String): Boolean = tool in supportedTools

    fun execute(tool: String, args: JSONObject, context: Context): String = when (tool) {
        "skills_list" -> listText(context)
        else -> "error: skills executor does not implement '$tool'."
    }

    fun listText(context: Context): String {
        val skills = SkillRegistry.list(context)
        if (skills.isEmpty()) return "No skills are registered yet. Open Skills Studio to create a reviewed skill."
        return skills.joinToString("\n") { skill ->
            "${skill.id} [${if (skill.enabled) "ENABLED" else "DISABLED"}] v${skill.version}: ${skill.description} | tools: ${skill.allowedTools.sorted().joinToString(", ")} | approval required: ${skill.requiresApproval}"
        }
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
