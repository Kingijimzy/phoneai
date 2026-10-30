package com.phoneai.app

import android.content.Context
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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


    @Test fun terminalStopCannotBeResumedOrStartAnotherTool() {
        assertTrue(AgentCheckpointStore.begin(context, "Open Settings"))
        assertTrue(AgentCheckpointStore.stop(context, "Stopped from Task Centre"))
        assertTrue("A terminal stop is no longer a pending resumable session", AgentCheckpointStore.pending(context) == null)
        assertFalse(AgentCheckpointStore.markRunning(context))
        assertFalse(AgentCheckpointStore.beginStep(context, "open_app", JSONObject()))
        assertFalse(AgentCheckpointStore.complete(context))
    }

    @Test fun atomicBackupRestoresCheckpointWhenBaseFileWasInterrupted() {
        assertTrue(AgentCheckpointStore.begin(context, "Inspect the current app state"))
        val base = File(context.filesDir, "orion_agent_checkpoint.json")
        val backup = File(context.filesDir, "orion_agent_checkpoint.json.bak")
        assertTrue(base.isFile)
        base.copyTo(backup, overwrite = true)
        assertTrue(base.delete())

        val recovered = AgentCheckpointStore.pending(context)
        assertNotNull(recovered)
        assertTrue(recovered!!.optString("command").contains("Inspect the current app state"))
        assertTrue("AtomicFile should restore its backup to the base path", base.isFile)
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

    @Test fun persistedStepResultsAreAvailableToTheExecutiveResumeContext() {
        assertTrue(AgentCheckpointStore.begin(context, "Inspect Settings and report the current state"))
        val args = JSONObject().put("name", "Settings")
        assertTrue(AgentCheckpointStore.beginStep(context, "open_app", args))
        assertTrue(AgentCheckpointStore.record(context, "open_app", args, "Opened Settings"))
        assertTrue(AgentCheckpointStore.pause(context))

        // The executive context must use the durable ledger, not merely the in-memory transcript.
        val resume = AgentCheckpointStore.resumeInstruction(context)
        assertNotNull(resume)
        assertTrue(resume!!.contains("Inspect Settings and report the current state"))
        assertTrue(resume.contains("open_app"))
        assertTrue(resume.contains("Opened Settings"))
    }

    @Test fun evidenceTaskIdentityIsUniqueAndStableAcrossResume() {
        assertTrue(AgentCheckpointStore.begin(context, "repeatable request"))
        val first = AgentCheckpointStore.evidenceTaskId(context)
        assertNotNull(first)
        assertTrue(AgentCheckpointStore.pause(context))
        assertEquals(first, AgentCheckpointStore.evidenceTaskId(context))
        assertTrue(AgentCheckpointStore.clear(context))
        assertTrue(AgentCheckpointStore.begin(context, "repeatable request"))
        val second = AgentCheckpointStore.evidenceTaskId(context)
        assertNotNull(second)
        assertNotEquals(first, second)
    }
}
