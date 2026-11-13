package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShellRunnerPolicyTest {
    @Test fun destructiveCommandsProduceWarningsInsteadOfDenials() {
        listOf(
            "rm -rf /system",
            "factory reset",
            "dd if=/dev/zero of=/dev/block/mmcblk0",
            "python3 -c \"open('/dev/block/mmcblk0','wb').write(b'x')\""
        ).forEach { command ->
            assertEquals(command, ShellRisk.WARNING, ShellPolicy.classify(command))
            assertNotNull(command, ShellPolicy.warning(command))
        }
    }

    @Test fun builtinTermuxAndLinuxRemainSelectableWithoutRiskTiers() {
        assertEquals(ShellExecutionRouting.Backend.BUILTIN, ShellExecutionRouting.backend("builtin"))
        assertEquals(ShellExecutionRouting.Backend.TERMUX, ShellExecutionRouting.backend("termux"))
        assertEquals(ShellExecutionRouting.Backend.LINUX, ShellExecutionRouting.backend("linux"))
        assertEquals(ShellExecutionRouting.Backend.LINUX, ShellExecutionRouting.backend("alpine"))
    }

    @Test fun builtinShellCanChangeToAnyDirectoryAllowedByAndroidUid() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        assertEquals("cwd: /", ShellRunner.runBuiltin(context, "cd /"))
        assertEquals("/", ShellRunner.runBuiltin(context, "pwd"))
    }

    @Test fun shellExitReceiptsPreserveSuccessFailureAndUnknownStates() {
        assertEquals(0, ShellRunner.exitCodeFromResult("EXIT 0 · SUCCESS\\ncommand output"))
        assertEquals(17, ShellRunner.exitCodeFromResult("exit=17\\ncompile failed"))
        assertEquals(23, ShellRunner.exitCodeFromResult("error: EXIT 23 · FAILED\\ncommand failed"))
        assertEquals(null, ShellRunner.exitCodeFromResult("command output says exit=0"))
        assertEquals(null, ShellRunner.exitCodeFromResult("error: command timed out"))
    }
}
