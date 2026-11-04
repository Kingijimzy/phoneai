package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class PythonToolBridgePolicyTest {
    @Test fun allowsRegisteredStyleNames() { assertNull(PythonToolBridgePolicy.check("open_app", 0)) }
    @Test fun blocksRecursionAndBadNames() {
        assertNotNull(PythonToolBridgePolicy.check("python_exec", 0))
        assertNotNull(PythonToolBridgePolicy.check("Open App", 0))
        assertNotNull(PythonToolBridgePolicy.check("", 0))
    }
    @Test fun capsCallsPerRun() {
        assertNull(PythonToolBridgePolicy.check("read_screen", PythonToolBridgePolicy.MAX_CALLS_PER_RUN - 1))
        assertNotNull(PythonToolBridgePolicy.check("read_screen", PythonToolBridgePolicy.MAX_CALLS_PER_RUN))
    }
}
