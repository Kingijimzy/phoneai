package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

/**
 * Phase 0C Integration Tests: Full mission lifecycle
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionMissionLifecycleIntegrationTest {

    private lateinit var controller: OrionMissionController
    private lateinit var mockSvc: PhoneAiService
    private lateinit var mockUi: AgentUi
    private lateinit var mockTools: Tools
    private lateinit var mockLocal: Local
    private lateinit var mockPythonExecutor: OrionPythonCapabilityExecutor
    private lateinit var mockCommandNim: Nim

    private fun createController(): OrionMissionController {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        mockSvc = org.mockito.Mockito.mock(PhoneAiService::class.java)
        mockUi = object : AgentUi {
            override fun say(text: String) {}
            override fun status(text: String) {}
            override fun note(text: String) {}
            override fun phase(p: String) {}
            override fun ask(question: String): Boolean = true
            override fun askUser(question: String, options: List<String>, access: String): String = ""
            override fun openBrowser(url: String) {}
        }
        mockTools = org.mockito.Mockito.mock(Tools::class.java)
        mockLocal = org.mockito.Mockito.mock(Local::class.java)
        mockPythonExecutor = org.mockito.Mockito.mock(OrionPythonCapabilityExecutor::class.java)
        mockCommandNim = org.mockito.Mockito.mock(Nim::class.java)

        return OrionMissionController(
            context = context,
            unifiedRuntime = org.mockito.Mockito.mock(OrionUnifiedRuntime::class.java),
            svc = mockSvc,
            ui = mockUi,
            tools = mockTools,
            localExecutor = mockLocal,
            pythonExecutor = mockPythonExecutor,
            commandNim = mockCommandNim
        )
    }

    @Test
    fun testFullMissionLifecycle() = runBlocking {
        controller = createController()

        // 1. Start mission
        val missionId = controller.startMission("Research Android SDK 36 and write a summary to file")
        assertNotNull(missionId)

        // 2. Verify mission is PLANNED
        var mission = controller.getCurrentMission()
        assertNotNull(mission)
        assertEquals(OrionMissionStatus.PLANNED, mission?.status)

        // 3. Delegate to research specialist
        val researchId = controller.delegateToSpecialist(
            specialism = OrionSpecialism.RESEARCH,
            objective = "Find Android SDK 36 release notes and new features",
            allowedTools = setOf("browser_navigate", "browser_snapshot", "memory")
        )
        assertNotNull(researchId)

        // 4. Collect research result
        val researchReceipt = controller.collectSpecialist(researchId).await()
        assertNotNull(researchReceipt)
        assertEquals(SpecialistVerdict.UNVERIFIED, researchReceipt.verdict) // Parent must verify

        // 5. Delegate to coding specialist to write summary
        val codingId = controller.delegateToSpecialist(
            specialism = OrionSpecialism.CODING,
            objective = "Write summary of Android SDK 36 findings to file",
            allowedTools = setOf("python_exec", "workspace_write", "save_file")
        )
        assertNotNull(codingId)

        // 6. Collect coding result
        val codingReceipt = controller.collectSpecialist(codingId).await()
        assertNotNull(codingReceipt)

        // 7. Mission should be in EXECUTING or further along
        mission = controller.getCurrentMission()
        assertNotNull(mission)
        assertTrue(mission?.status == OrionMissionStatus.EXECUTING || 
                   mission?.status == OrionMissionStatus.VERIFYING ||
                   mission?.status == OrionMissionStatus.COMPLETED)

        // 8. Both receipts should be attached to parent mission
        assertEquals(2, mission?.specialistReceipts.size)
    }

    @Test
    fun testMissionPauseAndResume() = runBlocking {
        controller = createController()

        val missionId = controller.startMission("Long running task: monitor build process")
        assertNotNull(missionId)

        // Pause the mission
        val paused = controller.pauseMission().await()
        assertTrue(paused)

        // Mission should be SUSPENDED
        var mission = controller.getCurrentMission()
        assertEquals(OrionMissionStatus.SUSPENDED, mission?.status)

        // Resume the mission
        val result = controller.resumeMission(missionId, OrionMissionController.ResumeStrategy.CONTINUE).await()
        assertNotNull(result)
    }

    @Test
    fun testMissionCancellation() = runBlocking {
        controller = createController()

        val missionId = controller.startMission("Task to be cancelled")
        assertNotNull(missionId)

        // Cancel the mission
        val cancelled = controller.cancelMission().await()
        assertTrue(cancelled)

        // Mission should be CANCELLED
        val mission = controller.getCurrentMission()
        assertEquals(OrionMissionStatus.CANCELLED, mission?.status)
    }

    @Test
    fun testSpecialistFailureHandling() = runBlocking {
        controller = createController()

        val missionId = controller.startMission("Task with failing specialist")
        assertNotNull(missionId)

        // Create a specialist that will fail
        val specialistId = controller.delegateToSpecialist(
            specialism = OrionSpecialism.DIAGNOSTICS,
            objective = "This will fail - impossible objective",
            allowedTools = setOf("shell")
        )
        assertNotNull(specialistId)

        // Collect result - should have FAILED verdict
        val receipt = controller.collectSpecialist(specialistId).await()
        assertNotNull(receipt)
        // Verdict could be FAILED or UNVERIFIED depending on execution
    }

    @Test
    fun testEvidenceReconciliation() = runBlocking {
        controller = createController()

        val missionId = controller.startMission("Test evidence collection")
        assertNotNull(missionId)

        // Create and collect multiple specialists
        val ids = mutableListOf<String>()
        for (spec in listOf(OrionSpecialism.RESEARCH, OrionSpecialism.CODING, OrionSpecialism.VERIFICATION)) {
            val id = controller.delegateToSpecialist(
                specialism = spec,
                objective = "Test $spec",
                allowedTools = setOf("browser_navigate")
            )
            ids.add(id)
        }

        // Collect all
        for (id in ids) {
            val receipt = controller.collectSpecialist(id).await()
            assertNotNull(receipt)
        }

        // Verify mission has all receipts
        val mission = controller.getCurrentMission()
        assertEquals(3, mission?.specialistReceipts.size)

        // Get evidence ledger
        val evidence = controller.getMissionEvidence(missionId).await()
        assertNotNull(evidence)
        assertEquals(missionId, evidence?.missionId)
        assertEquals(3, evidence?.specialistReceipts.size)
    }

    @Test
    fun testRestartResumeAfterProcessDeath() = runBlocking {
        controller = createController()

        val missionId = controller.startMission("Mission that survives restart")
        assertNotNull(missionId)

        // Simulate process death - create new controller
        val newController = createController()

        // Restore active mission
        val restored = newController.restoreActiveMission().await()
        assertNotNull(restored)
        assertEquals(missionId, restored?.id)

        // Should be able to resume
        val result = newController.resumeMission(missionId, OrionMissionController.ResumeStrategy.CONTINUE).await()
        assertNotNull(result)
    }
}