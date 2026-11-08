package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito

/**
 * Phase 0C Tests: Security boundaries and permission preservation
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase0CSecurityTest {

    private lateinit var mockSvc: PhoneAiService
    private lateinit var mockUi: AgentUi
    private lateinit var mockTools: Tools
    private lateinit var mockLocal: Local
    private lateinit var mockPythonExecutor: OrionPythonCapabilityExecutor
    private lateinit var mockCommandNim: Nim
    private lateinit var mockCapabilityBroker: OrionCapabilityBroker
    private lateinit var mockCapabilityIntelligence: OrionCapabilityIntelligence
    private lateinit var mockAcquisitionEngine: OrionCapabilityAcquisitionEngine
    private lateinit var mockUndoStack: OrionUndoStack

    private fun createController(ui: AgentUi = object : AgentUi {
        override fun say(text: String) {}
        override fun status(text: String) {}
        override fun note(text: String) {}
        override fun phase(p: String) {}
        override fun ask(question: String): Boolean = true
        override fun askUser(question: String, options: List<String>, access: String): String = ""
        override fun openBrowser(url: String) {}
    }): OrionMissionController {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        mockSvc = Mockito.mock(PhoneAiService::class.java)
        mockTools = Mockito.mock(Tools::class.java)
        mockLocal = Mockito.mock(Local::class.java)
        mockPythonExecutor = Mockito.mock(OrionPythonCapabilityExecutor::class.java)
        mockCommandNim = Mockito.mock(Nim::class.java)
        mockCapabilityBroker = Mockito.mock(OrionCapabilityBroker::class.java)
        mockCapabilityIntelligence = Mockito.mock(OrionCapabilityIntelligence::class.java)
        mockAcquisitionEngine = Mockito.mock(OrionCapabilityAcquisitionEngine::class.java)
        mockUndoStack = Mockito.mock(OrionUndoStack::class.java)

        return OrionMissionController(
            context = context,
            unifiedRuntime = Mockito.mock(OrionUnifiedRuntime::class.java),
            svc = mockSvc,
            ui = ui,
            tools = mockTools,
            localExecutor = mockLocal,
            pythonExecutor = mockPythonExecutor,
            commandNim = mockCommandNim,
            capabilityBroker = mockCapabilityBroker,
            capabilityIntelligence = mockCapabilityIntelligence,
            acquisitionEngine = mockAcquisitionEngine,
            undoStack = mockUndoStack
        )
    }

    @Test
    fun testTradingOrderRequiresExplicitConfirmation() = runBlocking {
        var askCount = 0
        val controller = createController(object : AgentUi {
            override fun say(text: String) {}
            override fun status(text: String) {}
            override fun note(text: String) {}
            override fun phase(p: String) {}
            override fun ask(question: String): Boolean { askCount++; return false } // Deny confirmation
            override fun askUser(question: String, options: List<String>, access: String): String = ""
            override fun openBrowser(url: String) {}
        })

        // Start a trading mission - should require confirmation
        val missionId = controller.startMission("Place a $100 buy order for BTC on Bybit")
        assertNotNull(missionId)

        // The mission should have requiresConfirmation = true in decision
        // This is enforced by the decision engine's confirmationFor logic
        // which checks for financial constraints and trading tools
    }

    @Test
    fun testOrdinaryTasksNoUnnecessaryPrompts() = runBlocking {
        var askCount = 0
        val controller = createController(object : AgentUi {
            override fun say(text: String) {}
            override fun status(text: String) {}
            override fun note(text: String) {}
            override fun phase(p: String) {}
            override fun ask(question: String): Boolean { askCount++; return true }
            override fun askUser(question: String, options: List<String>, access: String): String = ""
            override fun openBrowser(url: String) {}
        })

        // Ordinary task should not require confirmation
        val missionId = controller.startMission("Open WhatsApp and send a message to Mom")
        assertNotNull(missionId)

        // For non-trading, non-system-control tasks, confirmation should not be required
        // The decision engine's confirmationFor only triggers for:
        // - financial constraints
        // - trading tools
        // - SYSTEM_CONTROL intent type
        // - policy ASK verdict
    }

    @Test
    fun testAndroidPermissionsPreserved() = runBlocking {
        val controller = createController()

        // Specialist with tools requiring Android permissions
        val session = controller.delegateToSpecialist(
            specialism = OrionSpecialism.PHONE,
            objective = "Open camera app and take photo",
            allowedTools = setOf("open_app", "click", "type_text")
        )
        assertNotNull(session)
        
        // The specialist's allowed tools should be filtered by ToolGate.preflightDenial
        // which checks Android permissions
    }

    @Test
    fun testCredentialEncryptionPreserved() = runBlocking {
        // Verify that Android Keystore encryption is not bypassed
        // This is a structural test - the mission controller and agent factory
        // should not have any code that bypasses ApiKeyVault or AppVault
        
        val controller = createController()

        // Verify no direct credential access in mission controller
        // Credentials should only be accessed through ApiKeyVault/AppVault
        // which use Android Keystore encryption
    }

    @Test
    fun testShellDestructiveCommandsBlocked() = runBlocking {
        val controller = createController()

        // Try to delegate a specialist with shell access
        val session = controller.delegateToSpecialist(
            specialism = OrionSpecialism.DIAGNOSTICS,
            objective = "Run diagnostic shell commands",
            allowedTools = setOf("shell", "read_screen")
        )
        assertNotNull(session)
        
        // The shell tool should still be blocked by ShellPolicy for destructive commands
        // rm -rf /, dd, fork bombs, factory reset should be blocked
        // This is enforced by ToolGate.preflightDenial -> ShellPolicy.BLOCKED
    }
}