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
class ShellCommandStoreTest {

    private fun context(): Context = RuntimeEnvironment.getApplication()

    @Test fun recordsCommandWithRedactedSecrets() {
        val c = context()
        ShellCommandStore.clear(c)
        ShellCommandStore.record(c, "builtin", "curl https://api.example.com/v1?key=sk_live_abc123secret", 0, "OK", true)
        ShellCommandStore.record(c, "builtin", "export PASSWORD=hunter2 && echo done", 0, "done", true)

        val recent = ShellCommandStore.recent(c, 10)
        assertEquals(2, recent.size)

        // Secrets must be redacted, never stored in plaintext
        val joined = recent.joinToString("\n")
        assertFalse("API key must be redacted", joined.contains("sk_live_abc123secret"))
        assertFalse("Password must be redacted", joined.contains("hunter2"))
        assertTrue("URL should be redacted", joined.contains("[URL redacted]"))
        assertTrue("Command shell:builtin prefix present", joined.contains("builtin"))
    }

    @Test fun recordsExitCodeAndVerificationStatus() {
        val c = context()
        ShellCommandStore.clear(c)
        ShellCommandStore.record(c, "linux", "apk add python3", 0, "fetching... OK", true)
        ShellCommandStore.record(c, "termux", "rm -rf /", 1, "error: blocked", false)

        val recent = ShellCommandStore.recent(c, 10)
        assertTrue("verified success", recent.any { it.contains("exit=0") && it.contains("verified") })
        assertTrue("unverified failure", recent.any { it.contains("exit=1") && it.contains("unverified") })
    }

    @Test fun respectsMaxRecordLimit() {
        val c = context()
        ShellCommandStore.clear(c)
        repeat(5) { i ->
            ShellCommandStore.record(c, "builtin", "command $i", i % 2, "output $i", i % 2 == 0)
        }
        assertEquals(5, ShellCommandStore.count(c))
        assertEquals(5, ShellCommandStore.recent(c, 10).size)
        assertEquals(3, ShellCommandStore.recent(c, 3).size)
    }

    @Test fun clearRemovesAllRecords() {
        val c = context()
        ShellCommandStore.clear(c)
        ShellCommandStore.record(c, "builtin", "echo hello", 0, "hello", true)
        assertEquals(1, ShellCommandStore.count(c))
        ShellCommandStore.clear(c)
        assertEquals(0, ShellCommandStore.count(c))
        assertTrue(ShellCommandStore.recent(c, 10).isEmpty())
    }
}
