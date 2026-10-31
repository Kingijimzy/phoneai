package com.phoneai.app

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionAgentDelegationStoreTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        OrionAgentDelegation.clearForTest(context)
        AgentCheckpointStore.clear(context)
    }

    @Test fun specialistRunIsLinkedToParentAndPersistsCompletionEvidence() {
        assertTrue(AgentCheckpointStore.begin(context, "Research Android agent frameworks"))
        val parentId = AgentCheckpointStore.evidenceTaskId(context)
        val manifest = OrionAgentDelegation.create(
            AgentProfiles.find("research")!!, parentId, "Compare two public agent frameworks"
        )
        assertTrue(OrionAgentDelegation.begin(context, manifest))
        val running = OrionAgentDelegation.latest(context).last()
        assertEquals(parentId, running.optString("parentTaskId"))
        assertEquals("running", running.optString("status"))
        assertTrue(OrionAgentDelegation.finish(context, manifest, "Compared framework docs and repository activity"))
        val completed = OrionAgentDelegation.latest(context).last()
        assertEquals(manifest.id, completed.optString("id"))
        assertEquals("completed", completed.optString("status"))
        assertTrue(completed.optString("summary").contains("Compared framework docs"))
    }

    @Test fun parentPromptCanSeeBoundedWorkerReceiptsAcrossTurns() {
        val manifest = OrionAgentDelegation.create(
            AgentProfiles.find("coder")!!, "parent-123", "Review the Kotlin routing change", parentObjectiveId = "review_routing"
        )
        assertTrue(OrionAgentDelegation.begin(context, manifest))
        assertTrue(OrionAgentDelegation.finish(context, manifest, "Found a routing edge case and proposed a fix"))
        val summary = OrionAgentDelegation.promptSummary(context)
        assertTrue(summary.contains("SPECIALIST WORK LEDGER"))
        assertTrue(summary.contains("coder [completed]"))
        assertTrue(summary.contains("Found a routing edge case"))
        assertTrue(summary.contains("parent-123"))
        assertTrue(summary.contains("objective review_routing"))
        val saved = OrionAgentDelegation.latest(context).last()
        assertEquals("review_routing", saved.optString("parentObjectiveId"))
    }

    @Test fun failedSpecialistRunIsRecordedAsFailedNotCompleted() {
        val manifest = OrionAgentDelegation.create(
            AgentProfiles.find("research")!!, null, "Research one source"
        )
        assertTrue(OrionAgentDelegation.begin(context, manifest))
        assertTrue(OrionAgentDelegation.finish(context, manifest, "error: provider unavailable", failed = true))
        assertEquals("failed", OrionAgentDelegation.latest(context).last().optString("status"))
    }
}
