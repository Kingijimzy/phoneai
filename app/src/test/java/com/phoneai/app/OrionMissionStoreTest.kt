package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

/**
 * Phase 0C Tests: Mission Store persistence
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionMissionStoreTest {

    private lateinit var store: OrionMissionStore

    @Test
    fun testSaveAndLoadMission() = runBlocking {
        store = OrionMissionStore(org.robolectric.RuntimeEnvironment.getApplication())

        val mission = OrionMission.newMission(
            requestId = "test_req_1",
            intent = createMockIntent(),
            decision = createMockDecision()
        )

        store.save(mission)
        val loaded = store.getById(mission.id).await()
        
        assertNotNull(loaded)
        assertEquals(mission.id, loaded.id)
        assertEquals(mission.intent.primaryObjective, loaded.intent.primaryObjective)
        assertEquals(mission.status, loaded.status)
    }

    @Test
    fun testActiveMissionRetrieval() = runBlocking {
        store = OrionMissionStore(org.robolectric.RuntimeEnvironment.getApplication())

        val mission = OrionMission.newMission(
            requestId = "test_req_2",
            intent = createMockIntent(),
            decision = createMockDecision()
        ).withStatus(OrionMissionStatus.EXECUTING)

        store.save(mission)
        val active = store.getActiveMission().await()
        
        assertNotNull(active)
        assertEquals(mission.id, active.id)
        assertEquals(OrionMissionStatus.EXECUTING, active.status)
    }

    @Test
    fun testCompletedMissionNotActive() = runBlocking {
        store = OrionMissionStore(org.robolectric.RuntimeEnvironment.getApplication())

        val mission = OrionMission.newMission(
            requestId = "test_req_3",
            intent = createMockIntent(),
            decision = createMockDecision()
        ).withStatus(OrionMissionStatus.COMPLETED, completedAt = System.currentTimeMillis())

        store.save(mission)
        val active = store.getActiveMission().await()
        
        assertNull(active) // Completed missions are not "active"
    }

    @Test
    fun testChildMissions() = runBlocking {
        store = OrionMissionStore(org.robolectric.RuntimeEnvironment.getApplication())

        val parent = OrionMission.newMission(
            requestId = "parent_req",
            intent = createMockIntent(),
            decision = createMockDecision()
        )

        val child = parent.copy(
            id = "mission_child_1",
            parentMissionId = parent.id,
            intent = createMockIntent(),
            decision = createMockDecision()
        )

        store.save(parent)
        store.save(child)

        val children = store.getChildMissions(parent.id).await()
        assertEquals(1, children.size)
        assertEquals(child.id, children[0].id)
    }

    @Test
    fun testRecentMissions() = runBlocking {
        store = OrionMissionStore(org.robolectric.RuntimeEnvironment.getApplication())

        for (i in 1..5) {
            val mission = OrionMission.newMission(
                requestId = "req_$i",
                intent = createMockIntent(),
                decision = createMockDecision()
            )
            store.save(mission)
        }

        val recent = store.getRecent(3).await()
        assertEquals(3, recent.size)
        assertTrue(recent[0].createdAt >= recent[1].createdAt)
    }

    private fun createMockIntent(): OrionIntentIntelligence.Intent {
        return OrionIntentIntelligence.Intent(
            type = OrionIntentIntelligence.IntentType.ACTION,
            primaryObjective = "Test objective",
            entities = emptyMap(),
            entityRefs = emptyList(),
            constraints = emptyList(),
            dependencies = emptyList(),
            successCriteria = emptyList(),
            urgency = OrionIntentIntelligence.Urgency.NORMAL,
            confidence = 0.8,
            contextualReferences = emptyList(),
            requiredCapabilities = emptyList(),
            missingCapabilities = emptyList(),
            rationale = "Test intent"
        )
    }

    private fun createMockDecision(): OrionDecisionEngine.Decision {
        return OrionDecisionEngine.Decision(
            decisionId = "test_decision",
            timestamp = System.currentTimeMillis(),
            intent = createMockIntent(),
            reasoningResult = org.mockito.Mockito.mock(OrionReasoningEngine.ReasoningResult::class.java),
            selectedStrategy = null,
            executionPlan = null,
            requiresConfirmation = false,
            confirmationReason = null,
            rationale = "Test decision",
            alternativesRejected = emptyList(),
            policyChecks = emptyList(),
            canProceed = true,
            blocker = null
        )
    }
}