package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Test

class OrionCapabilityAwarenessTest {
    @Test fun firstEnvironmentObservationEstablishesBaseline() {
        assertEquals(emptyList<String>(), OrionCapabilityAwareness.diffEnvironment(
            null, mapOf("shizuku" to "NOT_RUNNING", "linux" to "READY")
        ))
    }

    @Test fun environmentTransitionsAreReportedWithoutTreatingUnknownAsReady() {
        val changes = OrionCapabilityAwareness.diffEnvironment(
            mapOf("shizuku" to "NOT_RUNNING", "linux" to "UNKNOWN", "network" to "VALIDATED"),
            mapOf("shizuku" to "READY", "linux" to "UNKNOWN", "network" to "DISCONNECTED")
        )
        assertEquals(
            listOf("network: VALIDATED -> DISCONNECTED", "shizuku: NOT_RUNNING -> READY"),
            changes
        )
    }

    @Test fun newlyDiscoveredEnvironmentKeysAreReported() {
        assertEquals(
            listOf("termux: NOT_OBSERVED -> PREFLIGHT_READY"),
            OrionCapabilityAwareness.diffEnvironment(emptyMap(), mapOf("termux" to "PREFLIGHT_READY"))
        )
    }

    @Test fun firstScanEstablishesBaselineWithoutCallingEverythingNew() {
        val (added, removed) = OrionCapabilityAwareness.diff(null, setOf("python_exec", "web_search"))
        assertEquals(emptyList<String>(), added)
        assertEquals(emptyList<String>(), removed)
    }

    @Test fun laterScansIdentifyAddedAndRemovedTools() {
        val (added, removed) = OrionCapabilityAwareness.diff(
            setOf("python_exec", "web_search"),
            setOf("python_exec", "browser_open")
        )
        assertEquals(listOf("browser_open"), added)
        assertEquals(listOf("web_search"), removed)
    }
}
