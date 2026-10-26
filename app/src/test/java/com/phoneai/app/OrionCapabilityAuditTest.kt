package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionCapabilityAuditTest {
    @Test fun fullyAvailableCapabilitiesAreReady() {
        val report = OrionCapabilityAudit.evaluate(
            registeredTools = 42,
            implementedPacks = 11,
            enabledPacks = 3,
            accessibilityReady = true,
            screenDisclosureAccepted = true,
            screenPerceptionReady = true
        )
        assertEquals(OrionCapabilityAudit.Status.READY, report.status)
        assertTrue(report.limitations.isEmpty())
        assertEquals(42, report.registeredTools)
    }

    @Test fun missingConsentOrPerceptionIsLimitedNotAutoApproved() {
        val report = OrionCapabilityAudit.evaluate(
            registeredTools = 42,
            implementedPacks = 11,
            enabledPacks = 3,
            accessibilityReady = true,
            screenDisclosureAccepted = false,
            screenPerceptionReady = false
        )
        assertEquals(OrionCapabilityAudit.Status.LIMITED, report.status)
        assertTrue(report.limitations.contains("screen_access_disclosure_not_accepted"))
        assertTrue(report.limitations.contains("screen_perception_unavailable"))
        assertFalse(report.screenDisclosureAccepted)
    }

    @Test fun missingCoreServiceOrRegistryBlocksReadiness() {
        val noService = OrionCapabilityAudit.evaluate(42, 11, 3, false, true, true)
        val noTools = OrionCapabilityAudit.evaluate(0, 11, 3, true, true, true)
        assertEquals(OrionCapabilityAudit.Status.BLOCKED, noService.status)
        assertEquals(OrionCapabilityAudit.Status.BLOCKED, noTools.status)
    }

    @Test fun invalidCountsAreClampedAndLimitationsDeduplicated() {
        val report = OrionCapabilityAudit.evaluate(-1, -2, 8, true, false, false)
        assertEquals(0, report.registeredTools)
        assertEquals(0, report.implementedPacks)
        assertEquals(0, report.enabledPacks)
        assertEquals(OrionCapabilityAudit.Status.BLOCKED, report.status)
        assertEquals(report.limitations.size, report.limitations.distinct().size)
    }
    @Test fun unavailableCurrentWindowCannotBeReportedAsReady() {
        val report = OrionCapabilityAudit.evaluate(
            registeredTools = 42,
            implementedPacks = 11,
            enabledPacks = 3,
            accessibilityReady = true,
            screenDisclosureAccepted = true,
            screenPerceptionReady = false
        )
        assertEquals(OrionCapabilityAudit.Status.LIMITED, report.status)
        assertTrue(report.limitations.contains("screen_perception_unavailable"))
    }

}
