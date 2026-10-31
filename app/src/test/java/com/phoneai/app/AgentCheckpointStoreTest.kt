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
        ExperienceStore.clear(context)
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

    @Test fun completedRecoveryStoresFailureCauseCorrectionAndVerificationForFutureTasks() {
        val request = "Open Settings and inspect notification access"
        assertTrue(AgentCheckpointStore.begin(context, request))

        val failedArgs = JSONObject().put("name", "Settings")
        assertTrue(AgentCheckpointStore.beginStep(context, "open_app", failedArgs))
        assertTrue(AgentCheckpointStore.record(context, "open_app", failedArgs, "error: target not found"))
        ExperienceStore.recordFailure(context, request, "open_app", "error: target not found")

        val correctedArgs = JSONObject().put("text", "Notification access")
        assertTrue(AgentCheckpointStore.beginStep(context, "click_text", correctedArgs))
        assertTrue(AgentCheckpointStore.record(context, "click_text", correctedArgs,
            "action returned\\nLOCAL VERIFY PASS: visible state changed after action."))
        assertTrue(AgentCheckpointStore.complete(context))

        val lesson = ExperienceStore.all(context).single()
        assertEquals("resolved_failure", lesson.kind)
        assertTrue(lesson.category.isNotBlank())
        assertTrue(lesson.correction.contains("open_app") && lesson.correction.contains("click_text"))
        assertTrue(lesson.verification.contains("completion gate passed"))
        val prompt = ExperienceStore.promptBlock(context, request)
        assertTrue(prompt.contains("LEARNED RESOLUTION"))
        assertTrue(prompt.contains("click_text"))
        assertTrue(prompt.contains("verification"))
    }

    @Test fun redactedPrivateFailureCanBeResolvedWithoutPersistingItsPayload() {
        val request = "Send a Telegram update and verify delivery"
        assertTrue(AgentCheckpointStore.begin(context, request))
        val privateArgs = JSONObject().put("text", "private message body")
        assertTrue(AgentCheckpointStore.beginStep(context, "telegram_send", privateArgs))
        assertTrue(AgentCheckpointStore.record(context, "telegram_send", privateArgs, "error: network timeout"))
        ExperienceStore.recordFailure(context, request, "telegram_send", "error: network timeout")

        val correctedArgs = JSONObject().put("text", "Delivery status")
        assertTrue(AgentCheckpointStore.beginStep(context, "click_text", correctedArgs))
        assertTrue(AgentCheckpointStore.record(context, "click_text", correctedArgs,
            "LOCAL VERIFY PASS: delivery status is visible."))
        assertTrue(AgentCheckpointStore.complete(context))

        val lesson = ExperienceStore.all(context).single()
        assertEquals("resolved_failure", lesson.kind)
        assertEquals("network_or_timeout", lesson.category)
        assertFalse(lesson.correction.contains("private message body"))
        assertFalse(lesson.verification.contains("private message body"))
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
    @Test fun parentPauseAndStopAreVisibleToChildDelegationByExactTaskId() {
        assertTrue(AgentCheckpointStore.begin(context, "Coordinate a research specialist"))
        val parentId = AgentCheckpointStore.evidenceTaskId(context)
        assertNotNull(parentId)
        assertFalse(AgentCheckpointStore.cancellationRequested(context, parentId))
        assertFalse(AgentCheckpointStore.cancellationRequested(context, "different-task"))
        assertTrue(AgentCheckpointStore.pause(context))
        assertTrue(AgentCheckpointStore.cancellationRequested(context, parentId))

        assertTrue(AgentCheckpointStore.clear(context))
        assertTrue(AgentCheckpointStore.begin(context, "Coordinate another specialist"))
        val stoppedParent = AgentCheckpointStore.evidenceTaskId(context)
        assertTrue(AgentCheckpointStore.stop(context, "User cancelled parent objective"))
        assertTrue(AgentCheckpointStore.cancellationRequested(context, stoppedParent))
    }

    @Test fun archivedPausedParentRemainsCancelledForItsDelegatedWorker() {
        assertTrue(AgentCheckpointStore.begin(context, "Parent objective"))
        val parentId = AgentCheckpointStore.evidenceTaskId(context)
        assertTrue(AgentCheckpointStore.pause(context))
        assertTrue(AgentCheckpointStore.begin(context, "Unrelated new objective"))
        assertTrue(AgentCheckpointStore.cancellationRequested(context, parentId))
    }

    @Test fun tasksExecutorOwnsDurableCheckpointRecoveryBoundary() {
        assertTrue(OrionTasksCapabilityExecutor.beginRun(context, "Inspect the app after restart"))
        assertTrue(OrionTasksCapabilityExecutor.beginStep(context, "open_app", JSONObject().put("name", "Settings")))
        assertTrue(OrionTasksCapabilityExecutor.needsRecoveryConfirmation(context))
        val pending = OrionTasksCapabilityExecutor.pendingCheckpoint(context)
        assertNotNull(pending)
        assertTrue(pending!!.has("inFlight"))

        assertTrue(OrionTasksCapabilityExecutor.acknowledgeRecovery(context, "I checked the phone and Settings is open"))
        assertFalse(OrionTasksCapabilityExecutor.needsRecoveryConfirmation(context))
        assertTrue(OrionTasksCapabilityExecutor.beginStep(context, "read_screen", JSONObject()))
    }



    @Test fun newTaskStartsWithoutDestroyingPausedWorkAndContinueRestoresOldest() {
        assertTrue(AgentCheckpointStore.begin(context, "Research Android AI and save the findings"))
        val originalId = AgentCheckpointStore.evidenceTaskId(context)
        val args = JSONObject().put("query", "Android on-device AI")
        assertTrue(AgentCheckpointStore.beginStep(context, "live_web_search", args))
        assertTrue(AgentCheckpointStore.record(context, "live_web_search", args, "Search results returned"))
        assertTrue(AgentCheckpointStore.pause(context))

        assertTrue("A separate task should start while the previous task remains archived",
            AgentCheckpointStore.begin(context, "Create an unrelated calculator"))
        assertEquals("Create an unrelated calculator", AgentCheckpointStore.pending(context)?.optString("command"))
        assertTrue(AgentCheckpointStore.hasArchivedPending(context))

        assertTrue(AgentCheckpointStore.complete(context))
        assertTrue(AgentCheckpointStore.activateOldestArchived(context))
        val preserved = AgentCheckpointStore.pending(context)
        assertNotNull(preserved)
        assertEquals("Research Android AI and save the findings", preserved!!.optString("command"))
        assertEquals(originalId, AgentCheckpointStore.evidenceTaskId(context))
        assertEquals(1, preserved.optJSONArray("steps")?.length())
        assertFalse(AgentCheckpointStore.hasArchivedPending(context))
    }

    @Test fun recoveryRequiredTaskCanBeParkedWhileAnotherTaskStarts() {
        assertTrue(AgentCheckpointStore.begin(context, "Send a message and inspect delivery"))
        assertTrue(AgentCheckpointStore.beginStep(context, "telegram_send", JSONObject().put("message", "hello")))
        assertTrue(AgentCheckpointStore.stop(context, "Process interrupted during send"))

        assertEquals("recovery_required", AgentCheckpointStore.pending(context)?.optString("status"))
        assertTrue(AgentCheckpointStore.begin(context, "Start another task"))
        assertEquals("Start another task", AgentCheckpointStore.pending(context)?.optString("command"))
        assertTrue(AgentCheckpointStore.hasArchivedPending(context))
        assertTrue(AgentCheckpointStore.complete(context))
        assertTrue(AgentCheckpointStore.activateOldestArchived(context))
        assertEquals("recovery_required", AgentCheckpointStore.pending(context)?.optString("status"))
        assertTrue(AgentCheckpointStore.needsRecoveryConfirmation(context))
    }
}
