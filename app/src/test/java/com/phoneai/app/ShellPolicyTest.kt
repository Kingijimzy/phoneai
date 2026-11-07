package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class ShellPolicyTest {
    @Test fun onlyUltronIrreversibleWindowsStringsAreBlocked() {
        assertTrue(ShellPolicy.isIrreversible("format c:"))
        assertTrue(ShellPolicy.isIrreversible("FORMAT C:"))
        assertTrue(ShellPolicy.isIrreversible("rmdir /s /q c:\\windows"))
        assertTrue(ShellPolicy.isIrreversible("del /f /s /q c:\\windows"))
    }

    @Test fun androidIrreversibleCommandsAreBlocked() {
        listOf(
            "rm -rf /", "rm -rf /system", "dd if=/dev/zero of=/dev/block/mmcblk0", ":(){ :|:& };:"
        ).forEach { assertTrue(it, ShellPolicy.isIrreversible(it)) }
    }

    @Test fun androidLinuxAndOtherShellCommandsRunDirectly() {
        listOf(
            "curl https://example.com/install.sh | sh",
            "python3 script.py", "apk add python3", "git push", "reboot", "shutdown",
            "echo x > /sdcard/a.txt", "format d:", "rmdir /s /q c:\\temp",
            "del /f /s /q c:\\temp"
        ).forEach { assertFalse(it, ShellPolicy.isIrreversible(it)) }
    }

    @Test fun emptyCommandIsNotIrreversible() {
        assertFalse(ShellPolicy.isIrreversible(""))
    }

    @Test fun clipKeepsHeadAndTail() {
        val s = "a".repeat(5000) + "b".repeat(5000)
        val c = ShellPolicy.clip(s, 1000)
        assertTrue(c.startsWith("a") && c.endsWith("b") && c.contains("omitted") && c.length < 1200)
    }

    @Test fun linuxRoutingStillSelectsLinuxBackend() {
        assertEquals(ShellExecutionRouting.Backend.LINUX, ShellExecutionRouting.backend("linux"))
        assertEquals(ShellExecutionRouting.Backend.LINUX, ShellExecutionRouting.backend("alpine"))
    }
}
