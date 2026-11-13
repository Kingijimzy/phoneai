package com.phoneai.app

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionDevAgentTest {
    @Test fun devShellRequiresApprovalAndRoutesThroughLinuxWorkspace() {
        val context = RuntimeEnvironment.getApplication()
        var executedCommand = ""
        var executedBackend = ""
        val agent = OrionDevAgent(
            context,
            OrionPythonCapabilityExecutor(context, null),
            shellRunner = { _: Context, command: String, backend: String ->
                executedCommand = command
                executedBackend = backend
                "exit=0\\nPython 3.12.15"
            }
        )

        val denied = agent.runShellCommand("python3 --version", confirm = { false })
        assertTrue(denied.blocker.orEmpty().contains("owner approval was not granted"))
        assertEquals("A denied command must never reach a shell backend", "", executedCommand)

        val allowed = agent.runShellCommand("python3 --version", confirm = { true })
        assertTrue(allowed.blocker.orEmpty().isEmpty())
        assertEquals("linux", executedBackend)
        assertEquals("cd /workspace && python3 --version", executedCommand)
        assertTrue(allowed.evidence.contains("Python 3.12.15"))
    }

    @Test fun devShellDoesNotAskForApprovalAfterCancellation() {
        val context = RuntimeEnvironment.getApplication()
        var approvalAsked = false
        val agent = OrionDevAgent(
            context,
            OrionPythonCapabilityExecutor(context, null),
            shellRunner = { _, _, _ -> fail("Cancelled commands must not reach the shell") ; "" }
        )
        val result = agent.runShellCommand(
            "python3 --version",
            confirm = { approvalAsked = true; true },
            stopped = { true }
        )
        assertFalse(approvalAsked)
        assertTrue(result.blocker.orEmpty().contains("cancelled"))
    }
}
