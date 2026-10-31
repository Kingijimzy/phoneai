package com.phoneai.app

import org.junit.Assert.assertTrue
import org.junit.Test

class OrionAppAwarenessTest {
    @Test fun toolMapSeparatesReadAndWriteByActualRegistryEntries() {
        val tools = listOf(
            ToolDefinition("read_screen", "read_screen {}", "screen", ToolAccess.READ),
            ToolDefinition("click", "click {id}", "click", ToolAccess.WRITE),
            ToolDefinition("live_web_search", "live_web_search {}", "search", ToolAccess.READ)
        )
        val map = OrionAppAwareness.toolArchitecture(tools)
        assertTrue(map.contains("Screen perception and interaction: READ [read_screen]; WRITE [click]"))
        assertTrue(map.contains("Web and navigation: READ [live_web_search]"))
    }

    @Test fun unknownToolsRemainVisibleInsteadOfBeingDropped() {
        assertTrue(OrionAppAwareness.domainForTool("future_capability").contains("Other registered"))
    }
    @Test fun explicitArchitectureRequestsEnableDetailedInventory() {
        assertTrue(OrionAppAwareness.isArchitectureRequest("Explain your app architecture and capabilities"))
        assertTrue(OrionAppAwareness.isArchitectureRequest("Teach me the app"))
        assertTrue(OrionAppAwareness.isArchitectureRequest("List your tools"))
        assertTrue(OrionAppAwareness.isArchitectureRequest("Teach me how to use Orion"))
        assertTrue(OrionAppAwareness.isArchitectureRequest("Walk me through the app"))
        assertTrue(OrionAppAwareness.isArchitectureRequest("What screens and modules are installed?"))
    }

    @Test fun ordinaryRequestsUseCompactAwareness() {
        assertTrue(!OrionAppAwareness.isArchitectureRequest("What is the weather today?"))
        assertTrue(!OrionAppAwareness.isArchitectureRequest("Help me analyse EURUSD"))
    }
}

