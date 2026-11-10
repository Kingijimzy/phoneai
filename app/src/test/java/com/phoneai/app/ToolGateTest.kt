package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolGateTest {
    @Test fun genericPackAndPerToolStatusIsRetired() {
        val summary = ToolGate.summarize(
            tools = listOf("shell", "open_app"),
            modeOf = { ToolMode.DENY },
            packOf = { "workspace_shell" },
            packOn = { false },
            titleOf = { it }
        )
        assertEquals("", summary)
    }
}
