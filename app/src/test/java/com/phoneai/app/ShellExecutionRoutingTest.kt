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

    @Test fun shellBackendRoutingHasNoApprovalTier() {
        assertEquals(ShellExecutionRouting.Backend.LINUX, ShellExecutionRouting.backend("linux"))
        assertEquals(ShellExecutionRouting.Backend.LINUX, ShellExecutionRouting.backend("Alpine"))
        assertFalse(ShellPolicy.isIrreversible("apk add python3"))
        assertFalse(ShellPolicy.isIrreversible("python3 script.py"))
        assertTrue(ShellPolicy.isIrreversible("format c:"))
    }

    @Test fun packageManagerCommandsGetLongerButBoundedTimeout() {
        assertEquals(180_000L, ShellExecutionRouting.commandTimeoutMs("apk add python3 git nodejs npm"))
        assertEquals(180_000L, ShellExecutionRouting.commandTimeoutMs("apk update && apk upgrade"))
        assertEquals(180_000L, ShellExecutionRouting.commandTimeoutMs("apk del python3"))
        assertEquals(180_000L, ShellExecutionRouting.commandTimeoutMs("npm install express"))
        assertEquals(180_000L, ShellExecutionRouting.commandTimeoutMs("pip install requests"))
        assertEquals(180_000L, ShellExecutionRouting.commandTimeoutMs("gradle test"))
        assertEquals(180_000L, ShellExecutionRouting.commandTimeoutMs("./gradlew assembleDebug"))
        assertEquals(60_000L, ShellExecutionRouting.commandTimeoutMs("python3 --version"))
        assertEquals(60_000L, ShellExecutionRouting.commandTimeoutMs("echo apk add python3"))
    }

    @Test fun packageMutationDetectionAvoidsCommandTextFalsePositives() {
        assertTrue(ShellExecutionRouting.isPackageOperation("apk add python3"))
        assertTrue(ShellExecutionRouting.isPackageOperation("apk update && apk upgrade"))
        assertTrue(ShellExecutionRouting.isPackageOperation("apk del python3"))
        assertFalse(ShellExecutionRouting.isPackageOperation("echo apk add python3"))
    }
}
