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

    @Test fun actionArgumentsResultsAndVerificationAreNeverPersisted() {
        val args = org.json.JSONObject()
            .put("api_key", "sk-live-secret")
            .put("password", "never-store-this")
            .put("target", "workspace/report.txt")
        ActionLogStore.record(
            context, "workspace_write", args,
            "Workspace file read-back verified.",
            OrionTaskOutcome.Status.COMPLETE
        )
        assertTrue(ActionLogStore.all(context).isEmpty())
        assertFalse(File(context.filesDir, "orion_action_log.json").exists())
    }

    @Test fun legacyShellActionRecordIsNotStored() {
        ActionLogStore.record(context, "shell", "command returned")
        assertTrue(ActionLogStore.all(context).isEmpty())
        assertFalse(File(context.filesDir, "orion_action_log.json").exists())
    }
}
