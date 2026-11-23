package com.phoneai.app

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Scripted conversations: what Kingi actually says, and what Orion's pure decision layer must do with it
 * (scene, tool surface, approvals). No model is called, so a failure here is a code regression, not model luck.
 */
class ScriptedConversationTest {
    private val none = emptyMap<String, Triple<Int, Int, Long>>()
    private fun surface(msg: String, prior: Set<String> = emptySet()) =
        ToolRegistry.focusedToolsFor(msg, prior, none).map { it.name }

    @Before fun reset() { OrionScenes.disabled = emptySet(); OrionScenes.briefOverrides = emptyMap() }
    @After fun cleanup() { reset() }

    private data class Line(val say: String, val scene: String?, val mustHave: List<String>)

    private val script = listOf(
        Line("I feel like coding", "coding", listOf("devspace", "shell", "python_exec", "extension_forge")),
        Line("let's trade", "trading", listOf("live_web_search", "recall")),
        Line("good morning", "morning", listOf("calendar_events", "task_list")),
        Line("I need to focus", "focus", listOf("quiet_period_set")),
        Line("let's play FC Mobile", "gaming", listOf("open_app", "quiet_period_set")),
        Line("I'm so frustrated with this", "tired", listOf("capability_search")),
        Line("what is 2 plus 2", null, listOf("capability_search", "ask_user", "recall"))
    )

    @Test fun everyLineGetsItsSceneAndKeepsCoreTools() {
        script.forEach { l ->
            assertEquals(l.say, l.scene, OrionScenes.detect(l.say)?.id)
            val names = surface(l.say)
            l.mustHave.forEach { assertTrue("'${l.say}' must expose $it, got $names", it in names) }
            assertTrue(names.size <= ToolRegistry.FOCUSED_LIMIT + OrionScenes.toolsFor(l.say).size)
        }
    }

    @Test fun aSceneNeverLeavesAnOrdinaryQuestionWithoutDiscovery() {
        val names = surface("what is 2 plus 2")
        assertTrue("capability_load" in names)
        assertNull(OrionScenes.detect("what is 2 plus 2"))
    }

    @Test fun switchedOffSceneStopsFiringMidConversation() {
        assertNotNull(OrionScenes.detect("I feel like coding"))
        OrionScenes.disabled = setOf("coding")
        assertNull(OrionScenes.detect("I feel like coding"))
        assertEquals("", OrionScenes.brief("I feel like coding"))
    }

    @Test fun ownerEditedBriefReplacesTheDefault() {
        OrionScenes.briefOverrides = mapOf("gaming" to "Just open it.")
        assertEquals("Just open it.", OrionScenes.brief("game time"))
    }

    @Test fun approvalsAreOnlyForIrreversibleLoss() {
        fun ask(tool: String, a: JSONObject) = IrreversibleActionConfirmationPolicy.ownerApprovalDescription(tool, a)
        // runs at once
        assertNull(ask("workspace_write", JSONObject().put("path", "a.py")))
        assertNull(ask("shell", JSONObject().put("command", "git push origin HEAD")))
        assertNull(ask("workspace_delete", JSONObject().put("path", "old.txt")))     // journaled, 'undo that'
        assertNull(ask("memory_delete", JSONObject().put("id", 4)))
        // asks first
        assertNotNull(ask("shell", JSONObject().put("command", "rm -rf /sdcard/DCIM")))
        assertNotNull(ask("trash_empty", JSONObject()))
        assertNotNull(ask("wipe_data", JSONObject()))
    }

    @Test fun undoableSetIsExactlyTheJournaledDeletes() {
        assertTrue(IrreversibleActionConfirmationPolicy.isUndoable("workspace_delete"))
        assertFalse(IrreversibleActionConfirmationPolicy.isUndoable("trash_empty"))
        assertFalse(IrreversibleActionConfirmationPolicy.isUndoable("shell"))
    }

    @Test fun githubCallsNeverSendOrionToTheBrowser() {
        // a 404 from GitHub must not produce device/browser alternatives (the wandering the owner saw)
        assertTrue(OrionRecoveryPlanner.selectAlternatives("github_account_read",
            "error: github 404 not found", setOf("live_web_search", "browser_navigate", "read_screen")).isEmpty())
    }
}
