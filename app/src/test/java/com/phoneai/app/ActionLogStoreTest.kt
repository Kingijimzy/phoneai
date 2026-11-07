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

    @Test fun recordsToolRedactedArgumentsResultAndVerificationStatus() {
        val args = org.json.JSONObject()
            .put("api_key", "sk-live-secret")
            .put("password", "never-log-this")
            .put("target", "workspace/report.txt")
        ActionLogStore.record(
            context, "workspace_write", args,
            "Workspace file read-back verified.",
            OrionTaskOutcome.Status.COMPLETE
        )
        val event = ActionLogStore.all(context).first()
        assertEquals("workspace_write", event.tool)
        assertTrue("Unexpected sanitized arguments: ${event.arguments}", event.arguments.contains("report.txt"))
        assertTrue(event.arguments.contains("[redacted]"))
        assertFalse(event.arguments.contains("sk-live-secret"))
        assertFalse(event.arguments.contains("never-log-this"))
        assertTrue(event.result.contains("Private result omitted; returned"))
        assertEquals("COMPLETE", event.verificationStatus)
    }

    @Test fun legacyActionRecordIsNeverMarkedVerified() {
        ActionLogStore.record(context, "shell", "command returned")
        val event = ActionLogStore.all(context).first()
        assertEquals("BLOCKED_OR_UNVERIFIED", event.verificationStatus)
    }
}
