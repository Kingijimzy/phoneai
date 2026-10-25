package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillAuthorizationPolicyTest {
    private val supported = setOf("time", "battery", "open_url", "task_delete")
    private val consequential = setOf("open_url", "task_delete")

    private fun skill(
        enabled: Boolean = true,
        tools: Set<String> = setOf("time", "open_url"),
        maxCalls: Int = 3,
        maxDurationMs: Long = 10_000,
        requiresApproval: Boolean = false
    ) = PhoneAiSkill(
        id = "safe-helper",
        version = "1.0.0",
        description = "A test skill",
        allowedTools = tools,
        maxCalls = maxCalls,
        maxDurationMs = maxDurationMs,
        requiresApproval = requiresApproval,
        enabled = enabled
    )

    private fun check(
        skill: PhoneAiSkill? = skill(),
        tool: String = "time",
        calls: Int = 0,
        elapsed: Long = 0,
        approved: Boolean = false
    ) = SkillAuthorizationPolicy.evaluate(
        skill, tool, calls, elapsed, approved, supported, consequential
    )

    @Test fun skillAllowlistTracksTheRealToolRegistryWithoutAllowingArbitraryCode() {
        assertTrue(SkillRegistry.isSupportedTool("browser_snapshot"))
        assertTrue(SkillRegistry.isSupportedTool("browser_click"))
        assertTrue(SkillRegistry.isSupportedTool("notification_action"))
        assertFalse(SkillRegistry.isSupportedTool("run_shell"))
        assertFalse(SkillRegistry.isSupportedTool("execute_python"))
    }

    @Test fun missingOrDisabledSkillIsDenied() {
        assertEquals("Skill is not registered.", check(skill = null).reason)
        assertEquals("Skill is disabled.", check(skill = skill(enabled = false)).reason)
    }

    @Test fun unknownAndUnlistedToolsAreDenied() {
        assertFalse(check(tool = "run_shell").allowed)
        assertFalse(check(tool = "battery").allowed)
    }

    @Test fun callAndTimeBudgetsAreEnforcedAtBoundary() {
        assertTrue(check(calls = 2).allowed)
        assertFalse(check(calls = 3).allowed)
        assertFalse(check(calls = -1).allowed)
        assertTrue(check(elapsed = 10_000).allowed)
        assertFalse(check(elapsed = 10_001).allowed)
        assertFalse(check(elapsed = -1).allowed)
    }

    @Test fun consequentialToolsRequireApprovalEveryRun() {
        assertEquals("User approval is required before this action.", check(tool = "open_url").reason)
        assertTrue(check(tool = "open_url", approved = true).allowed)
        assertFalse(check(tool = "task_delete").allowed)
    }

    @Test fun skillLevelApprovalAppliesToOtherwiseOrdinaryTools() {
        assertFalse(check(skill = skill(requiresApproval = true)).allowed)
        assertTrue(check(skill = skill(requiresApproval = true), approved = true).allowed)
    }

    @Test fun ordinaryAllowlistedToolPassesWithoutApproval() {
        assertTrue(check().allowed)
    }
}
