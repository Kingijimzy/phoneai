package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

/**
 * Phase 0C Tests: Agent Factory and Specialist Runtime
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionAgentFactoryTest {

    private lateinit var factory: OrionAgentFactory
    private lateinit var mockSvc: PhoneAiService
    private lateinit var mockUi: AgentUi
    private lateinit var mockTools: Tools
    private lateinit var mockLocal: Local
    private lateinit var mockPythonExecutor: OrionPythonCapabilityExecutor
    private lateinit var mockCommandNim: Nim
    private lateinit var mockController: OrionMissionController

    @Test
    fun testCreateSpecialistSession() = runBlocking {
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
        mockController = org.mockito.Mockito.mock(OrionMissionController::class.java)

        factory = OrionAgentFactory(
            context = context,
            svc = mockSvc,
            ui = mockUi,
            tools = mockTools,
            localExecutor = mockLocal,
            pythonExecutor = mockPythonExecutor,
            commandNim = mockCommandNim,
            controller = mockController
        )

        val session = factory.createSession(
            parentMissionId = "mission_123",
            specialism = OrionSpecialism.RESEARCH,
            objective = "Research Python async patterns",
            allowedTools = setOf("browser_navigate", "browser_snapshot"),
            contextSnapshot = null
        )

        assertNotNull(session)
        assertEquals("mission_123", session.parentMissionId)
        assertEquals(OrionSpecialism.RESEARCH, session.specialism)
        assertEquals("Research Python async patterns", session.objective)
        assertTrue(session.allowedTools.contains("browser_navigate"))
        assertEquals(SpecialistStatus.STARTING, session.status)
    }

    @Test
    fun testCollectSpecialistResult() = runBlocking {
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
        mockController = org.mockito.Mockito.mock(OrionMissionController::class.java)

        factory = OrionAgentFactory(
            context = context,
            svc = mockSvc,
            ui = mockUi,
            tools = mockTools,
            localExecutor = mockLocal,
            pythonExecutor = mockPythonExecutor,
            commandNim = mockCommandNim,
            controller = mockController
        )

        val session = factory.createSession(
            parentMissionId = "mission_123",
            specialism = OrionSpecialism.CODING,
            objective = "Write a Python test",
            allowedTools = setOf("python_exec", "workspace_write"),
            contextSnapshot = null
        )

        // Wait a bit for specialist to run
        Thread.sleep(100)

        val receipt = factory.collectSession(session.sessionId).await()
        assertNotNull(receipt)
        assertEquals(session.sessionId, receipt.sessionId)
        assertEquals("mission_123", receipt.parentMissionId)
        assertEquals(OrionSpecialism.CODING, receipt.specialism)
    }

    @Test
    fun testTerminateSpecialist() = runBlocking {
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
        mockController = org.mockito.Mockito.mock(OrionMissionController::class.java)

        factory = OrionAgentFactory(
            context = context,
            svc = mockSvc,
            ui = mockUi,
            tools = mockTools,
            localExecutor = mockLocal,
            pythonExecutor = mockPythonExecutor,
            commandNim = mockCommandNim,
            controller = mockController
        )

        val session = factory.createSession(
            parentMissionId = "mission_123",
            specialism = OrionSpecialism.DIAGNOSTICS,
            objective = "Diagnose issue",
            allowedTools = setOf("read_screen", "shell"),
            contextSnapshot = null
        )

        val receipt = factory.terminateSession(session.sessionId, "Owner cancelled").await()
        assertNotNull(receipt)
        assertEquals(SpecialistVerdict.CANCELLED, receipt.verdict)
        assertTrue(receipt.result.contains("Cancelled"))
    }

    @Test
    fun testConcurrencyLimit() = runBlocking {
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
        mockController = org.mockito.Mockito.mock(OrionMissionController::class.java)

        // Mock controller to return canSpawnMore = false for 4th specialist
        org.mockito.Mockito.`when`(mockController.MAX_CONCURRENT_SPECIALISTS).thenReturn(3)
        
        factory = OrionAgentFactory(
            context = context,
            svc = mockSvc,
            ui = mockUi,
            tools = mockTools,
            localExecutor = mockLocal,
            pythonExecutor = mockPythonExecutor,
            commandNim = mockCommandNim,
            controller = mockController
        )

        // Create 3 specialists (should succeed)
        for (i in 1..3) {
            val session = factory.createSession(
                parentMissionId = "mission_123",
                specialism = OrionSpecialism.RESEARCH,
                objective = "Task $i",
                allowedTools = setOf("browser_navigate"),
                contextSnapshot = null
            )
            assertNotNull(session)
        }

        // 4th should fail
        assertThrows<IllegalStateException> {
            factory.createSession(
                parentMissionId = "mission_123",
                specialism = OrionSpecialism.RESEARCH,
                objective = "Task 4",
                allowedTools = setOf("browser_navigate"),
                contextSnapshot = null
            )
        }
    }
}