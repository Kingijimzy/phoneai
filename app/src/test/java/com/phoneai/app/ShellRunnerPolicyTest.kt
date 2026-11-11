package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShellRunnerPolicyTest {
    @Test fun destructiveCommandsAreNotRejectedByShellPolicyBeforeBackendSelection() {
        val context = RuntimeEnvironment.getApplication()
        listOf("builtin", "termux", "linux").forEach { backend ->
            val result = ShellRunner.runStreaming(context, "rm -rf '/system'", backend) { }
            assertFalse("$backend must not return the former hard-block response",
                result.startsWith("error: blocked by Orion shell hard blocks"))
            assertTrue("$backend must at least surface the warning",
                result.contains("WARNING:") || result.contains("error:") || result.contains("EXIT"))
        }
    }

    @Test fun rawDeviceWriteGetsWarningRatherThanAClassifierDenial() {
        val warning = ShellPolicy.warning("""python3 -c "open('/dev/block/mmcblk0','wb').write(b'x')" """)
        assertTrue(warning?.contains("WARNING:") == true)
    }
}
