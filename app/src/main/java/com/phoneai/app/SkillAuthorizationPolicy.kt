package com.phoneai.app

/**
 * Pure authorization decision logic shared by the runtime registry and JVM unit tests.
 * This checks permission only; it never executes a tool or grants approval.
 */
internal object SkillAuthorizationPolicy {
    fun evaluate(
        skill: PhoneAiSkill?,
        toolName: String,
        callsUsed: Int,
        elapsedMs: Long,
        userApprovedThisRun: Boolean,
        supportedTools: Set<String>,
        consequentialTools: Set<String>
    ): SkillDecision {
        if (skill == null) return SkillDecision(false, "Skill is not registered.")
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
}
