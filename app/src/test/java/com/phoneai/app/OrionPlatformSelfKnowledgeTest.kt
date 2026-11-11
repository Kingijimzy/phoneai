package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionPlatformSelfKnowledgeTest {
    @Test fun capabilityLayersAreDerivedFromRegisteredAdapterRoutes() {
        val tools = setOf("read_screen", "python_exec", "mcp_status", "made_up_capability")
        val routes = tools.mapNotNull { OrionCapabilityAdapters.route(it) }
        val map = OrionPlatformSelfKnowledge.capabilityLayerMap(tools, routes)

        assertTrue(map.contains("device: read_screen"))
        assertTrue(map.contains("python: python_exec"))
        assertTrue(map.contains("mcp: mcp_status"))
        assertTrue(map.contains("made_up_capability"))
        assertTrue(map.contains("registered_tools=4"))
        assertTrue(map.contains("adapter_routed=3"))
        assertTrue(map.contains("unclassified=1"))
    }

    @Test fun everyAdvertisedAdapterRoutePointsToARegisteredTool() {
        val registered = ToolRegistry.all.map { it.name }.toSet()
        val routes = OrionCapabilityAdapters.routes()

        assertTrue(routes.isNotEmpty())
        assertTrue(routes.all { it.tool in registered })
        assertEquals(routes.size, routes.map { it.tool }.distinct().size)
        assertTrue(routes.map { it.backend }.toSet().containsAll(
            setOf(
                OrionCapabilityAdapters.Backend.DEVICE,
                OrionCapabilityAdapters.Backend.PYTHON,
                OrionCapabilityAdapters.Backend.MCP,
                OrionCapabilityAdapters.Backend.MEMORY,
                OrionCapabilityAdapters.Backend.TASKS
            )
        ))
    }

    @Test fun identityStatementsAreRememberedAsOwnerConfirmed() {
        val c = MemoryAutoIndexer.extract("My name is Kingi and I live in Lagos")
        assertEquals("fact", c?.category)
        assertTrue(c?.ownerConfirmed == true)
    }
}
