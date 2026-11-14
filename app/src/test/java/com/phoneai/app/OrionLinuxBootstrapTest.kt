package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Test

class OrionLinuxBootstrapTest {
    @Test fun detectsMissingNodeAndPip() {
        assertEquals(listOf("nodejs"), OrionLinuxBootstrap.missingPackages("node app.js", "/bin/sh: node: not found"))
        assertEquals(listOf("py3-pip"), OrionLinuxBootstrap.missingPackages("pip install requests", "sh: pip: not found"))
    }
    @Test fun ignoresUnrelatedFailures() {
        assertEquals(emptyList<String>(), OrionLinuxBootstrap.missingPackages("python3 x.py", "Traceback: ValueError"))
        assertEquals("apk add --no-cache git", OrionLinuxBootstrap.installCommand(listOf("git")))
    }
}
