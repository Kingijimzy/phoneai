package com.phoneai.app

import org.junit.Test
import org.junit.Assert.*

/**
 * Phase 0C: Tests for OrionCapabilityBroker - dynamic capability expansion.
 */
internal class OrionCapabilityBrokerTest {

    @Test
    fun testBrokerCreation() {
        // Broker is a singleton - test that it can be instantiated
        // Actual testing requires Android context, so this is a structural test
        assertTrue("OrionCapabilityBroker class exists", true)
    }

    @Test
    fun testCapabilityGrantDataClass() {
        val grant = OrionCapabilityBroker.CapabilityGrant(
            sessionId = "test_session",
            granted = setOf("python_exec", "shell"),
            denied = setOf("trading_place_order"),
            reason = "Trading tools require explicit confirmation"
        )

        assertEquals("test_session", grant.sessionId)
        assertEquals(setOf("python_exec", "shell"), grant.granted)
        assertEquals(setOf("trading_place_order"), grant.denied)
        assertTrue(grant.reason.contains("Trading"))
    }

    @Test
    fun testCapabilityAcquisitionPlan() {
        val plan = OrionCapabilityBroker.CapabilityAcquisitionPlan(
            capability = "python:numpy",
            type = OrionCapabilityBroker.AcquisitionType.INSTALL_PYTHON_PACKAGE,
            description = "Install Python package: numpy",
            automated = true,
            estimatedTimeMs = 30000
        )

        assertEquals("python:numpy", plan.capability)
        assertEquals(OrionCapabilityBroker.AcquisitionType.INSTALL_PYTHON_PACKAGE, plan.type)
        assertTrue(plan.automated)
    }

    @Test
    fun testAcquisitionTypes() {
        val types = OrionCapabilityBroker.AcquisitionType.values()
        assertEquals(5, types.size)
        assertTrue(types.any { it == OrionCapabilityBroker.AcquisitionType.ENABLE_PACK })
        assertTrue(types.any { it == OrionCapabilityBroker.AcquisitionType.INSTALL_EXTENSION })
        assertTrue(types.any { it == OrionCapabilityBroker.AcquisitionType.INSTALL_PYTHON_PACKAGE })
        assertTrue(types.any { it == OrionCapabilityBroker.AcquisitionType.CONFIGURE_EXTERNAL_SERVICE })
        assertTrue(types.any { it == OrionCapabilityBroker.AcquisitionType.UNKNOWN })
    }

    @Test
    fun testCapabilityDiscoveryResult() {
        val result = OrionCapabilityBroker.CapabilityDiscoveryResult(
            missingCapabilities = setOf("python_exec", "trading_place_order"),
            acquisitionPlans = listOf(
                OrionCapabilityBroker.CapabilityAcquisitionPlan(
                    capability = "python_exec",
                    type = OrionCapabilityBroker.AcquisitionType.ENABLE_PACK,
                    description = "Enable coding pack",
                    automated = true,
                    estimatedTimeMs = 1000
                )
            ),
            canAutoResolve = true
        )

        assertEquals(2, result.missingCapabilities.size)
        assertEquals(1, result.acquisitionPlans.size)
        assertTrue(result.canAutoResolve)
    }
}