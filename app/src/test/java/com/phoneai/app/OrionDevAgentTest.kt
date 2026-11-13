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
                "exit=0\nPython 3.12.15"
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

    @Test fun devShellRejectsNonZeroLinuxExitStatus() {
        val context = RuntimeEnvironment.getApplication()
        val agent = OrionDevAgent(
            context,
            OrionPythonCapabilityExecutor(context, null),
            shellRunner = { _, _, _ -> "exit=17\ncompile failed" }
        )

        val result = agent.runShellCommand("gradle test", confirm = { true })
        assertTrue(result.blocker.orEmpty().contains("exited with status 17"))
        assertTrue(result.evidence.contains("compile failed"))
        assertFalse(result.summary.contains("exited with status 0"))
    }

    @Test fun devShellRejectsMissingExitStatusAsUnverified() {
        val context = RuntimeEnvironment.getApplication()
        val agent = OrionDevAgent(
            context,
            OrionPythonCapabilityExecutor(context, null),
            shellRunner = { _, _, _ -> "build finished (maybe)" }
        )

        val result = agent.runShellCommand("gradle test", confirm = { true })
        assertTrue(result.blocker.orEmpty().contains("no verifiable exit status"))
        assertFalse(result.summary.contains("exited with status 0"))
    }

    @Test fun repositoryInspectionUsesSelectedProjectSubdirectory() {
        val context = RuntimeEnvironment.getApplication()
        val workspace = OrionPythonWorkspace(context)
        assertTrue(workspace.write("project/build.gradle.kts", "plugins {}").startsWith("saved "))
        assertTrue(workspace.write("project/src/Main.kt", "fun main() = Unit").startsWith("saved "))
        assertTrue(workspace.write("other/README.md", "not this project").startsWith("saved "))

        val agent = OrionDevAgent(context, OrionPythonCapabilityExecutor(context, null))
        val result = agent.understandRepository("project")

        assertTrue(result.blocker.orEmpty().isBlank())
        assertTrue(result.evidence.contains("Android/Kotlin Gradle"))
        assertTrue(result.evidence.contains("build.gradle.kts"))
        assertTrue(result.evidence.contains("src/Main.kt"))
        assertFalse(result.evidence.contains("README.md"))
    }

    @Test fun repositoryInspectionRejectsWorkspaceTraversal() {
        val context = RuntimeEnvironment.getApplication()
        val workspace = OrionPythonWorkspace(context)
        assertTrue(workspace.write("project/Main.kt", "fun main() = Unit").startsWith("saved "))

        val agent = OrionDevAgent(context, OrionPythonCapabilityExecutor(context, null))
        val result = agent.understandRepository("../")

        assertTrue(result.blocker.orEmpty().contains("invalid or missing workspace directory"))
    }

    @Test fun devShellReportsBuildAndTestAsSeparateVerifiedOutcomes() {
        val context = RuntimeEnvironment.getApplication()
        val agent = OrionDevAgent(
            context,
            OrionPythonCapabilityExecutor(context, null),
            shellRunner = { _, command, _ ->
                if (command.contains("assembleDebug")) "exit=0\nBUILD SUCCESSFUL"
                else "exit=0\nTests run: 24, failures: 0"
            }
        )

        val build = agent.runShellCommand("./gradlew :app:assembleDebug", verification = "build", confirm = { true })
        assertTrue(build.compilationAttempted)
        assertTrue(build.compilationPassed)
        assertFalse(build.testsAttempted)
        assertTrue(build.statusLine().contains("compiled OK"))
        assertTrue(build.summary.contains("Build command exited with status 0"))

        val tests = agent.runShellCommand("./gradlew testDebugUnitTest", verification = "test", confirm = { true })
        assertTrue(tests.testsAttempted)
        assertTrue(tests.testsPassed)
        assertFalse(tests.compilationAttempted)
        assertTrue(tests.statusLine().contains("tests passed"))
    }

    @Test fun devShellRejectsFalseBuildClassificationAndReportsFailedBuild() {
        val context = RuntimeEnvironment.getApplication()
        var executed = false
        val agent = OrionDevAgent(
            context,
            OrionPythonCapabilityExecutor(context, null),
            shellRunner = { _, _, _ -> executed = true; "exit=1\ncompile failed" }
        )

        val mismatch = agent.runShellCommand("python3 --version", verification = "build", confirm = { true })
        assertFalse(executed)
        assertTrue(mismatch.blocker.orEmpty().contains("does not match"))

        val failed = agent.runShellCommand("./gradlew :app:assembleDebug", verification = "build", confirm = { true })
        assertTrue(failed.compilationAttempted)
        assertFalse(failed.compilationPassed)
        assertTrue(failed.statusLine().contains("compilation failed"))
    }
}
