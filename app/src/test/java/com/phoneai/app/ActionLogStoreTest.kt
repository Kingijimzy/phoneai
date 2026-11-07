package com.phoneai.app

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActionLogStoreTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "orion_action_log.json").delete()
        ActionLogStore.clear(context)
    }

    @Test fun secretsAreRedactedBeforeStorage() {
        val args = org.json.JSONObject()
            .put("api_key", "sk-live-secret")
            .put("password", "never-store-this")
            .put("target", "workspace/report.txt")
        ActionLogStore.record(
            context, "workspace_write", args,
            "Workspace file read-back verified.",
            OrionTaskOutcome.Status.COMPLETE
        )
        val raw = File(context.filesDir, "orion_action_log.json").readText()
        assertFalse(raw.contains("sk-live-secret"))
        assertFalse(raw.contains("never-store-this"))
        val e = ActionLogStore.all(context).single()
        assertEquals("workspace_write", e.tool)
        assertEquals("ok", e.outcome)
        assertTrue(e.arguments.contains("report.txt"))
    }

    @Test fun legacyRecordIsStoredAndFailuresMarked() {
        ActionLogStore.record(context, "shell", "error: not found")
        val e = ActionLogStore.all(context).single()
        assertEquals("failed", e.outcome)
    }

    @Test fun logIsCappedAndNeverFedToPrompts() {
        repeat(130) { ActionLogStore.record(context, "t$it", "ok") }
        assertEquals(100, ActionLogStore.all(context).size)
        assertEquals("t129", ActionLogStore.all(context).first().tool)
        assertEquals("", ActionLogStore.recentFailureHints(context))
    }
}
