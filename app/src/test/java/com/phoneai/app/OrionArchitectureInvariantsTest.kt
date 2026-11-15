package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the single-runtime architecture: nothing registered may be unrouted or without an executor. */
class OrionArchitectureInvariantsTest {
    @Test fun everyRegisteredToolRoutesToAConcreteExecutor() {
        val broken = ToolRegistry.all.map { it.name }.filter { name ->
            val route = OrionCapabilityAdapters.route(name)
            route == null || !OrionRuntimeCapabilityExecutor.supports(route.backend, name)
        }
        assertTrue("Unrouted or executor-less tools: $broken", broken.isEmpty())
    }

    @Test fun socialConnectorSharesTheIntegrationsBackend() {
        assertEquals(OrionCapabilityAdapters.Backend.INTEGRATIONS, OrionCapabilityAdapters.route("social")?.backend)
        assertNotNull(ToolRegistry.find("social"))
    }

    @Test fun unattendedSurfacesAreNotOwnerInitiated() {
        assertTrue(OrionCoordinator.Surface.CHAT.ownerInitiated)
        assertTrue(!OrionCoordinator.Surface.INITIATIVE.ownerInitiated && !OrionCoordinator.Surface.AUTONOMY.ownerInitiated)
    }
}
