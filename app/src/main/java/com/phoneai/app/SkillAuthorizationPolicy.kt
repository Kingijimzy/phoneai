package com.phoneai.app

/**
 * Pure skill-run validation shared by the runtime registry and JVM tests.
 * It checks registration and execution budgets; there are no per-tool scopes or approval checks.
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
        if (toolName !in supportedTools) {
            return SkillDecision(false, "Tool is not registered in Orion's shared tool registry.")
        }
        if (callsUsed < 0 || callsUsed >= skill.maxCalls) {
            return SkillDecision(false, "Skill call budget reached.")
        }
        if (elapsedMs < 0 || elapsedMs > skill.maxDurationMs) {
            return SkillDecision(false, "Skill time budget reached.")
        }
        return SkillDecision(true, "Allowed.")
    }
}
