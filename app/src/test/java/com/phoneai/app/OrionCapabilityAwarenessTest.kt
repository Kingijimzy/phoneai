package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Test

class OrionCapabilityAwarenessTest {
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
