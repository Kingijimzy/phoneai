package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionSkillsCapabilityExecutorTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun clearRegistry() {
        context.getSharedPreferences("phoneai_skill_registry_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun skillCreateValidatesAndAutoEnablesDeclarativeWorkflow() {
        clearRegistry()
        val result = OrionSkillsCapabilityExecutor.execute(
            "skill_create",
            JSONObject()
                .put("id", "local_report")
                .put("version", "1.0.0")
                .put("description", "Read local project files and summarize their status.")
                .put("allowed_tools", JSONArray().put("workspace_read").put("read_screen"))
                .put("max_calls", 8)
                .put("max_duration_ms", 45_000)
                .put("requires_approval", false),
            context
        )
        assertTrue(result, result.startsWith("Created and auto-enabled validated skill"))
        val skill = SkillRegistry.list(context).single { it.id == "local_report" }
        assertTrue(skill.enabled)
        assertEquals(setOf("workspace_read", "read_screen"), skill.allowedTools)
    }

    @Test fun skillCannotAddUnknownOrRegistryManagementTools() {
        clearRegistry()
        val result = OrionSkillsCapabilityExecutor.execute(
            "skill_create",
            JSONObject()
                .put("id", "unsafe_skill")
                .put("version", "1.0.0")
                .put("description", "Attempt to call an unregistered tool.")
                .put("allowed_tools", JSONArray().put("skill_enable")),
            context
        )
        assertTrue(result.startsWith("error:"))
        assertTrue(SkillRegistry.list(context).none { it.id == "unsafe_skill" })
    }

    @Test fun enablingAWorkflowDoesNotBypassWriteToolApproval() {
        clearRegistry()
        val result = OrionSkillsCapabilityExecutor.execute(
            "skill_create",
            JSONObject()
                .put("id", "workspace_helper")
                .put("version", "1.0.0")
                .put("description", "Read and update files in the project workspace.")
                .put("allowed_tools", JSONArray().put("workspace_read").put("workspace_write")),
            context
        )
        assertTrue(result.startsWith("Created and auto-enabled"))
        val read = SkillRegistry.authorize(context, "workspace_helper", "workspace_read", 0, 0L, false)
        val write = SkillRegistry.authorize(context, "workspace_helper", "workspace_write", 0, 0L, false)
        assertTrue(read.allowed)
        assertFalse(write.allowed)
        assertTrue(write.reason.contains("approval", ignoreCase = true))
    }

    @Test fun skillCanBeDisabledAndReenabledWithoutChangingToolPermissions() {
        clearRegistry()
        SkillRegistry.register(context, PhoneAiSkill(
            id = "safe_reader",
            version = "1.0.0",
            description = "Read project files.",
            allowedTools = setOf("workspace_read")
        ))
        assertTrue(OrionSkillsCapabilityExecutor.execute("skill_enable", JSONObject().put("id", "safe_reader"), context).contains("enabled"))
        assertNotNull(OrionSkillsCapabilityExecutor.beginRun(context, "safe_reader"))
        assertTrue(OrionSkillsCapabilityExecutor.execute("skill_disable", JSONObject().put("id", "safe_reader"), context).contains("disabled"))
        assertNull(OrionSkillsCapabilityExecutor.beginRun(context, "safe_reader"))
    }
}
