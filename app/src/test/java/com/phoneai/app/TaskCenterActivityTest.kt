package com.phoneai.app

import android.widget.Button
import android.view.View
import android.view.ViewGroup
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskCenterActivityTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() {
        AgentCheckpointStore.clear(context)
    }

    private fun buttons(view: View): List<Button> {
        val found = mutableListOf<Button>()
        fun visit(node: View) {
            if (node is Button) found += node
            if (node is ViewGroup) for (i in 0 until node.childCount) visit(node.getChildAt(i))
        }
        visit(view)
        return found
    }

    @Test fun taskBoardPauseAndResumeControlsDoNotInventExecution() {
        val task = TaskStore.add(context, "UI control test ${UUID.randomUUID()}")!!
        assertTrue(TaskStore.update(context, task.id, status = "running"))
        val activity = Robolectric.buildActivity(TaskCenterActivity::class.java).setup().get()

        val pause = buttons(activity.window.decorView).firstOrNull { it.text.toString().trim().endsWith("Pause") }
        assertNotNull("Running task must expose a Pause control", pause)
        pause!!.performClick()
        assertEquals("paused", TaskStore.all(context).first { it.id == task.id }.status)

        val resume = buttons(activity.window.decorView).firstOrNull { it.text.toString().trim().endsWith("Resume") }
        assertNotNull("Paused task must expose a Resume control", resume)
        resume!!.performClick()
        // Without a connected execution service, Resume must not falsely mark work as running.
        assertEquals("paused", TaskStore.all(context).first { it.id == task.id }.status)
    }

    @Test fun staleRunningTaskWithoutWorkerOrCheckpointReturnsToQueue() {
        val task = TaskStore.add(context, "Stale task ${UUID.randomUUID()}")!!
        assertTrue(TaskStore.update(context, task.id, status = "running"))
        val activity = Robolectric.buildActivity(TaskCenterActivity::class.java).setup().get()

        val open = buttons(activity.window.decorView).firstOrNull { it.text.toString().trim() == "Open run" }
        assertNotNull("Running task must expose an Open run control", open)
        open!!.performClick()
        assertEquals("queued", TaskStore.all(context).first { it.id == task.id }.status)
    }

    @Test fun verifiedTaskOutcomeIsPersistedAndVisibleOnTheBoard() {
        val task = TaskStore.add(context, "Verified task ${UUID.randomUUID()}")!!
        val evidenceSummary = "Orion's task-level completion gate passed. Review the chat transcript."
        assertTrue(TaskStore.update(context, task.id, status = "completed", resultSummary = evidenceSummary))
        val restored = TaskStore.all(context).first { it.id == task.id }
        assertEquals(evidenceSummary, restored.resultSummary)

        val activity = Robolectric.buildActivity(TaskCenterActivity::class.java).setup().get()
        val rendered = mutableListOf<String>()
        fun collect(view: View) {
            if (view is android.widget.TextView) rendered += view.text.toString()
            if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i))
        }
        collect(activity.window.decorView)
        assertTrue("The task card must show its saved completion evidence", rendered.any { it.contains(evidenceSummary) })
        assertTrue("The card must distinguish agent-verified completion from manual completion",
            rendered.any { it.contains("Agent completion gate passed") })
    }

    @Test fun stopControlMarksInflightActionForRecoveryInsteadOfDiscardingIt() {
        assertTrue(AgentCheckpointStore.begin(context, "Open Settings and inspect the Android version"))
        assertTrue(AgentCheckpointStore.beginStep(context, "open_app", JSONObject().put("package", "com.android.settings")))
        val activity = Robolectric.buildActivity(TaskCenterActivity::class.java).setup().get()

        val stop = buttons(activity.window.decorView).firstOrNull { it.text.toString() == "Stop active run" }
        assertNotNull("Saved agent session must expose a terminal Stop control", stop)
        stop!!.performClick()

        val checkpoint = AgentCheckpointStore.pending(context)
        assertNotNull(checkpoint)
        assertEquals("recovery_required", checkpoint!!.optString("status"))
        assertNotNull(checkpoint.optJSONObject("inFlight"))
        assertTrue(checkpoint.optString("recoveryNotice").contains("outcome is unknown"))
    }

    @Test fun pauseControlPausesCheckpointAndRetainsUncertainInflightEvidence() {
        assertTrue(AgentCheckpointStore.begin(context, "Open Settings and inspect the Android version"))
        assertTrue(AgentCheckpointStore.beginStep(context, "open_app", JSONObject().put("package", "com.android.settings")))
        val activity = Robolectric.buildActivity(TaskCenterActivity::class.java).setup().get()

        val stop = buttons(activity.window.decorView).firstOrNull { it.text.toString() == "Pause active run" }
        assertNotNull("Saved agent session must expose a Pause control", stop)
        stop!!.performClick()

        val checkpoint = AgentCheckpointStore.pending(context)
        assertNotNull(checkpoint)
        assertEquals("paused", checkpoint!!.optString("status"))
        assertNotNull("Stop must retain the in-flight marker for safe reconciliation", checkpoint.optJSONObject("inFlight"))
        assertTrue(AgentCheckpointStore.resumeInstruction(context)!!.contains("outcome is unknown"))
        assertFalse("Pause must prevent a new tool from starting", AgentCheckpointStore.beginStep(context, "read_screen", JSONObject()))
        assertTrue(AgentCheckpointStore.acknowledgeRecovery(context, "I checked the phone and confirmed the screen state"))
        assertTrue(AgentCheckpointStore.markRunning(context))
        assertTrue("Explicit resume may continue after recovery is acknowledged",
            AgentCheckpointStore.beginStep(context, "read_screen", JSONObject()))
    }
}
