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
}
