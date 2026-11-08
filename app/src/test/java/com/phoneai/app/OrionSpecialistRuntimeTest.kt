package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

/**
 * Phase 0C Tests: Specialist Runtime execution
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionSpecialistRuntimeTest {

    private lateinit var runtime: OrionSpecialistRuntime
    private lateinit var mockSvc: PhoneAiService
    private lateinit var mockUi: AgentUi
    private lateinit var mockTools: Tools
    private lateinit var mockLocal: Local
    private lateinit var mockPythonExecutor: OrionPythonCapabilityExecutor
    private lateinit var mockCommandNim: Nim

    @Test
    fun testExecuteDynamicSpecialist() = runBlocking {
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

        runtime = OrionSpecialistRuntime(
            context = context,
            svc = mockSvc,
            ui = mockUi,
            tools = mockTools,
            localExecutor = mockLocal,
            pythonExecutor = mockPythonExecutor,
            commandNim = mockCommandNim
        )

        val session = OrionAgentFactory.SpecialistSession(
            sessionId = "spec_test_1",
            parentMissionId = "mission_123",
            specialism = OrionSpecialism.RESEARCH,
            objective = "Research Kotlin coroutines",
            allowedTools = setOf("browser_navigate", "browser_snapshot", "memory"),
            contextSnapshot = null,
            createdAt = System.currentTimeMillis(),
            status = SpecialistStatus.STARTING
        )

        val result = runtime.executeSpecialist(session).await()
        
        assertNotNull(result)
        assertEquals("spec_test_1", result.sessionId)
        assertEquals(OrionSpecialism.RESEARCH, result.specialism)
        assertTrue(result.status == SpecialistStatus.COMPLETED || result.status == SpecialistStatus.FAILED)
    }

    @Test
    fun testBuiltInSpecialistWorkers() = runBlocking {
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

        runtime = OrionSpecialistRuntime(
            context = context,
            svc = mockSvc,
            ui = mockUi,
            tools = mockTools,
            localExecutor = mockLocal,
            pythonExecutor = mockPythonExecutor,
            commandNim = mockCommandNim
        )

        val workers = runtime.getBuiltInWorkers()
        assertNotNull(workers)
        // Should have 5 built-in specialists
        assertTrue(workers.size >= 5)
    }
}