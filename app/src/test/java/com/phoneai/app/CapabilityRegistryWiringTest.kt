package com.phoneai.app

import com.phoneai.app.CapabilityRegistry.State
import org.junit.Assert.*
import org.junit.Test

class CapabilityRegistryWiringTest {
    @Test fun toolOnItsOnPackIsUsable() {
        val e = CapabilityRegistry.forBuiltInTool("shell", denied = false, packOn = true)
        assertTrue(e.usable)
        assertEquals(State.AVAILABLE, e.state)
    }

    @Test fun deniedToolIsNotUsableEvenWhenItsPackIsOn() {
        val e = CapabilityRegistry.forBuiltInTool("shell", denied = true, packOn = true)
        assertFalse(e.usable)
        assertEquals(State.DISABLED, e.state)
    }

    @Test fun toolWhosePackIsOffIsNotUsable() {
        val e = CapabilityRegistry.forBuiltInTool("shell", denied = false, packOn = false)
        assertFalse(e.usable)
    }

    @Test fun builtInToolsAreNeverReportedAsSetupOrPermissionBlocked() {
        listOf(true, false).forEach { denied ->
            listOf(true, false).forEach { on ->
                val s = CapabilityRegistry.forBuiltInTool("time", denied, on).state
                assertFalse(s == State.SETUP_REQUIRED || s == State.PERMISSION_REQUIRED)
            }
        }
    }
}
