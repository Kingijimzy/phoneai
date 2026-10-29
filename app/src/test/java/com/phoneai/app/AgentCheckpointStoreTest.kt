package com.phoneai.app

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises the durable checkpoint file across fresh reads, not just the transition policy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentCheckpointStoreTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        AgentCheckpointStore.clear(context)
    }

    @Test fun interruptedInFlightActionRequiresInspectionAndIsNotAutomaticallyReplayed() {
        assertTrue(AgentCheckpointStore.begin(context, "Open an app and inspect its state"))
        assertTrue(AgentCheckpointStore.beginStep(context, "open_app", JSONObject().put("name", "Settings")))

        // Read the persisted file again as a newly loaded checkpoint would after process death.
        val recovered = AgentCheckpointStore.pending(context)
        assertNotNull(recovered)
        assertTrue(recovered!!.has("inFlight"))
        assertTrue(AgentCheckpointStore.needsRecoveryConfirmation(context))
        assertFalse("uncertain side effects must never be marked complete", AgentCheckpointStore.complete(context))

        assertTrue(AgentCheckpointStore.acknowledgeRecovery(context, "I checked the phone and Settings is open"))
        val acknowledged = AgentCheckpointStore.pending(context)
        assertNotNull(acknowledged)
        assertFalse(acknowledged!!.has("inFlight"))
        assertFalse(AgentCheckpointStore.needsRecoveryConfirmation(context))

        // A new attempt is allowed only after the uncertain action has been acknowledged.
        assertTrue(AgentCheckpointStore.beginStep(context, "read_screen", JSONObject()))
    }

    @Test fun checkpointRedactsPrivateArgumentsBeforeWritingThemToDisk() {
        assertTrue(AgentCheckpointStore.begin(context, "Send a message"))
        assertTrue(AgentCheckpointStore.beginStep(
            context, "sms", JSONObject().put("number", "+2348012345678").put("text", "private message")
        ))
        val checkpoint = AgentCheckpointStore.pending(context)!!
        val args = checkpoint.getJSONObject("inFlight").getString("args")
        assertFalse(args.contains("+2348012345678"))
        assertFalse(args.contains("private message"))
        assertTrue(args.contains("redacted"))
    }
}
