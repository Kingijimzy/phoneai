package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class ShellRunnerPolicyTest {
    @Test fun onlyUltronWindowsIrreversibleStringsAreRejected() {
        assertTrue(ShellPolicy.isIrreversible("format c:"))
        assertTrue(ShellPolicy.isIrreversible("rmdir /s /q c:\\windows"))
        assertTrue(ShellPolicy.isIrreversible("del /f /s /q c:\\windows"))
    }

    @Test fun ordinaryAndroidAndLinuxCommandsAreNotRejectedByShellPolicy() {
        listOf("ls", "apk add python3", "python3 script.py", "reboot", "shutdown")
            .forEach { assertFalse(it, ShellPolicy.isIrreversible(it)) }
    }
}
