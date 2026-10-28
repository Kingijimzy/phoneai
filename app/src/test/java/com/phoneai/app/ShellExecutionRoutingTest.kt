package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class ShellExecutionRoutingTest {
    @Test fun selectsEachBackendExplicitly() {
        assertEquals(ShellExecutionRouting.Backend.BUILTIN, ShellExecutionRouting.backend(null))
        assertEquals(ShellExecutionRouting.Backend.BUILTIN, ShellExecutionRouting.backend("builtin"))
        assertEquals(ShellExecutionRouting.Backend.LINUX, ShellExecutionRouting.backend("linux"))
        assertEquals(ShellExecutionRouting.Backend.LINUX, ShellExecutionRouting.backend("Alpine"))
        assertEquals(ShellExecutionRouting.Backend.TERMUX, ShellExecutionRouting.backend("termux"))
    }

    @Test fun unknownBackendIsNotSilentlyDowngraded() {
        assertEquals(ShellExecutionRouting.Backend.INVALID, ShellExecutionRouting.backend("docker"))
    }

    @Test fun everyLinuxAliasRequiresApproval() {
        assertTrue(ShellExecutionRouting.requiresApproval("linux"))
        assertTrue(ShellExecutionRouting.requiresApproval("Alpine"))
        assertFalse(ShellExecutionRouting.requiresApproval("builtin"))
        assertFalse(ShellExecutionRouting.requiresApproval("termux"))
    }

    @Test fun packageManagerCommandsGetLongerButBoundedTimeout() {
        assertEquals(180_000L, ShellExecutionRouting.commandTimeoutMs("apk add python3 git nodejs npm"))
        assertEquals(180_000L, ShellExecutionRouting.commandTimeoutMs("apk update && apk upgrade"))
        assertEquals(180_000L, ShellExecutionRouting.commandTimeoutMs("apk del python3"))
        assertEquals(60_000L, ShellExecutionRouting.commandTimeoutMs("python3 --version"))
        assertEquals(60_000L, ShellExecutionRouting.commandTimeoutMs("echo apk add python3"))
    }

    @Test fun packageMutationDetectionAvoidsCommandTextFalsePositives() {
        assertTrue(ShellExecutionRouting.isPackageOperation("apk add python3"))
        assertTrue(ShellExecutionRouting.isPackageOperation("apk update && apk upgrade"))
        assertTrue(ShellExecutionRouting.isPackageOperation("apk del python3"))
        assertFalse(ShellExecutionRouting.isPackageOperation("echo apk add python3"))
    }

    @Test fun aiTermuxBridgeAcceptsOnlyExactReadOnlyDiagnostics() {
        assertTrue(TermuxCommandPolicy.isReadOnly("pwd"))
        assertTrue(TermuxCommandPolicy.isReadOnly("python3 --version"))
        assertFalse(TermuxCommandPolicy.isReadOnly("pwd; rm -rf ."))
        assertFalse(TermuxCommandPolicy.isReadOnly("git status && git clean -fd"))
        assertFalse(TermuxCommandPolicy.isReadOnly("apk add python3"))
    }
}
