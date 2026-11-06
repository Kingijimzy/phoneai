package com.phoneai.app

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShellRunnerPolicyTest {
    @Test fun catastrophicCommandsAreBlockedBeforeEveryBackendIsSelected() {
        val context = RuntimeEnvironment.getApplication()
        listOf("builtin", "termux", "linux").forEach { backend ->
            val result = ShellRunner.runStreaming(context, "rm -rf '/system'", backend) { }
            assertTrue("$backend must apply the hard block before dispatch", result.startsWith("error: blocked by Orion shell hard blocks"))
        }
    }

    @Test fun inlineRawDeviceWritesAreBlockedBeforeLinuxProutDispatch() {
        val context = RuntimeEnvironment.getApplication()
        val result = ShellRunner.runStreaming(
            context,
            """python3 -c "open('/dev/block/mmcblk0','wb').write(b'x')" """,
            "linux"
        ) { }
        assertTrue(result.startsWith("error: blocked by Orion shell hard blocks"))
    }
}
