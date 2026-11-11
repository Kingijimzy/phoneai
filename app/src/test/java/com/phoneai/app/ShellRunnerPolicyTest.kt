package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

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
}
