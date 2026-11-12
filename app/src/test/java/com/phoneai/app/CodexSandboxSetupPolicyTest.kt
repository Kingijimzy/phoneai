package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class CodexSandboxSetupPolicyTest {
    @Test fun provisionsAlpineStaticBubblewrapAndExposesExpectedPath() {
        val command = CodexSandboxSetupPolicy.ensureCommand
        assertTrue(command.contains("bubblewrap-static"))
        assertTrue(command.contains("bwrap.static"))
        assertTrue(command.contains("/usr/local/bin/bwrap"))
        assertTrue(command.contains("bwrap --version"))
    }

    @Test fun missingBinaryDoesNotPassAsReady() {
        assertTrue(CodexSandboxSetupPolicy.ensureCommand.contains("test -n"))
        assertTrue(CodexSandboxSetupPolicy.ensureCommand.contains("exit 42"))
    }
}
